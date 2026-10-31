package me.ymid.rutea.app.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.AreaScenarioFixture;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

class BreakdownReplannerTest {

    @TempDir
    static Path dir;

    private static AreaScenario scenario;
    private static Solution solution;
    private static ShiftScheduler.Schedule schedule;

    @BeforeAll
    static void plan() throws IOException {
        scenario = AreaScenarioFixture.town(dir, 20, 1_600);
        solution = new AlnsSolver().solve(scenario.instance(), new SolverSettings(2, Duration.ofMinutes(1), 200));
        schedule = ShiftScheduler.schedule(scenario, solution, BogotaFigures.UNLOAD_SECONDS_LOW, scenario.operation().shiftSeconds(), 3);
    }

    @Test
    void timelineAddsUpToTheTruckShift() {
        ShiftScheduler.Truck truck = schedule.trucks().getFirst();
        List<ShiftScheduler.Visit> visits = ShiftScheduler.timeline(scenario, solution, truck, schedule.unloadSeconds(), 0);

        assertThat(visits).extracting(ShiftScheduler.Visit::arriveSeconds).isSorted();
        int stops = Arrays.stream(truck.trips()).map(solution::routeLength).sum();
        assertThat(visits).hasSize(stops);
        ShiftScheduler.Visit last = visits.getLast();
        long end = last.arriveSeconds() + BogotaFigures.SERVICE_SECONDS_PER_CONTAINER
                + scenario.travel().seconds(last.node(), scenario.gate()) + schedule.unloadSeconds()
                + scenario.travel().seconds(scenario.gate(), 0);
        assertThat(end).isEqualTo(truck.seconds());
    }

    @Test
    void aBreakdownBeforeLeavingOrphansEveryContainerOfTheTruck() {
        ShiftScheduler.Truck truck = schedule.trucks().getFirst();
        BreakdownReplanner.Replan replan = BreakdownReplanner.afterBreakdown(scenario, solution, schedule, 0, 0, 100, 1);

        int stops = Arrays.stream(truck.trips()).map(solution::routeLength).sum();
        assertThat(replan.orphans()).hasSize(stops);
        int[] covered = replan.reserve().trucks().stream()
                .flatMapToInt(t -> Arrays.stream(t.trips()).flatMap(k -> Arrays.stream(route(replan.trips(), k))))
                .sorted().toArray();
        assertThat(covered).isEqualTo(Arrays.stream(replan.orphans()).sorted().toArray());
    }

    @Test
    void aBreakdownAfterTheLastStopOrphansNothing() {
        long afterShift = schedule.trucks().getFirst().seconds();

        BreakdownReplanner.Replan replan = BreakdownReplanner.afterBreakdown(scenario, solution, schedule, 0,
                afterShift, 100, 1);

        assertThat(replan.orphans()).isEmpty();
        assertThat(replan.reserve().trucks()).isEmpty();
        assertThat(replan.meetsRecoveryRule()).isTrue();
    }

    @Test
    void replannedTripsRespectCapacityFromTheWarmStart() {
        BreakdownReplanner.Replan replan = BreakdownReplanner.afterBreakdown(scenario, solution, schedule, 0, 0, 100, 1);
        Instance part = BreakdownReplanner.subInstance(scenario.instance(), replan.orphans());

        assertThat(Evaluator.evaluate(part, BreakdownReplanner.inPlanOrder(part)).violations()).isEmpty();
        for (int k = 0; k < replan.trips().routeCount(); k++) {
            int load = Arrays.stream(route(replan.trips(), k)).map(scenario.instance()::demand).sum();
            assertThat(load).isLessThanOrEqualTo(scenario.instance().capacity());
        }
    }

    @Test
    @DisplayName("R05 · D. 1077/2015 art. 2.3.2.2.2.3.34: la reserva llega en máximo 3 horas")
    void reserveArrivesWithinThreeHoursAfterDispatch() {
        BreakdownReplanner.Replan replan = BreakdownReplanner.afterBreakdown(scenario, solution, schedule, 0, 600, 100, 1);

        assertThat(replan.firstPickupSeconds()).isGreaterThanOrEqualTo(600 + BogotaFigures.RESERVE_DISPATCH_SECONDS);
        assertThat(replan.meetsRecoveryRule()).isTrue();
    }

    private static int[] route(Solution s, int k) {
        int[] stops = new int[s.routeLength(k)];
        for (int p = 0; p < stops.length; p++) {
            stops[p] = s.customer(k, p);
        }
        return stops;
    }
}
