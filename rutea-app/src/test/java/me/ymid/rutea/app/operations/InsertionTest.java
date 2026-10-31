package me.ymid.rutea.app.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.operations.BreakdownReplanner.Strategy;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.AreaScenarioFixture;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

class InsertionTest {

    @TempDir
    static Path dir;

    private static AreaScenario scenario;
    private static Solution solution;
    private static ShiftScheduler.Schedule schedule;

    @BeforeAll
    static void plan() throws IOException {
        // Cargas chicas: los viajes quedan con capacidad de sobra para absorber huérfanos.
        scenario = AreaScenarioFixture.town(dir, 24, 400);
        solution = new AlnsSolver().solve(scenario.instance(), new SolverSettings(4, Duration.ofMinutes(1), 200));
        schedule = ShiftScheduler.schedule(scenario, solution, BogotaFigures.UNLOAD_SECONDS_LOW, scenario.operation().shiftSeconds(), 1);
    }

    @Test
    void livePlanReproducesTheScheduledShifts() {
        LivePlan live = LivePlan.of(scenario, solution, schedule);

        for (int t = 0; t < live.truckCount(); t++) {
            assertThat(live.endSeconds(t)).isEqualTo(schedule.trucks().get(t).seconds());
            assertThat(live.meters(t)).isEqualTo(schedule.trucks().get(t).meters());
        }
    }

    @Test
    void insertsOnlyWithinCapacityAndShiftAndNeverIntoTheExcludedTruck() {
        LivePlan live = LivePlan.of(scenario, solution, schedule);
        List<Integer> pending = new ArrayList<>(Arrays.stream(stops(schedule.trucks().getFirst())).boxed().toList());
        long before = live.meters();

        List<Insertion.Placement> placed = Insertion.cheapest(live, pending, 0, 0);

        assertThat(placed).isNotEmpty().allSatisfy(p -> assertThat(p.truck()).isNotZero());
        for (int t = 0; t < live.truckCount(); t++) {
            assertThat(live.endSeconds(t)).isLessThanOrEqualTo(live.shiftSeconds());
            for (int i = 0; i < live.tripCount(t); i++) {
                assertThat(live.tripLoad(t, i)).isLessThanOrEqualTo(scenario.instance().capacity());
            }
        }
        long detours = placed.stream().mapToLong(Insertion.Placement::extraMeters).sum();
        assertThat(live.meters() - before).isEqualTo(detours);
    }

    @Test
    void neverInsertsIntoThePastOfARoute() {
        LivePlan live = LivePlan.of(scenario, solution, schedule);
        long afterEveryone = schedule.trucks().stream().mapToLong(ShiftScheduler.Truck::seconds).max().orElseThrow();
        List<Integer> pending = new ArrayList<>(List.of(stops(schedule.trucks().getFirst())[0]));

        assertThat(Insertion.cheapest(live, pending, afterEveryone, 0)).isEmpty();
        assertThat(pending).hasSize(1);
    }

    @Test
    void reassignIfNoReserveFallsBackToReserveOnlyWhenNotEverythingFits() {
        BreakdownReplanner.Replan avoid = BreakdownReplanner.afterBreakdown(scenario, solution, schedule, 0, 0, 100, 1,
                Strategy.REASSIGN_IF_NO_RESERVE);
        BreakdownReplanner.Replan first = BreakdownReplanner.afterBreakdown(scenario, solution, schedule, 0, 0, 100, 1,
                Strategy.REASSIGN_FIRST);

        if (avoid.reserve().trucks().isEmpty()) {
            assertThat(avoid.reassigned()).hasSize(avoid.orphans().length);
        } else {
            assertThat(avoid.reassigned()).isEmpty();
        }
        // Reasignar primero: cada huérfano queda en un camión en servicio o en un viaje de reserva, nunca en los dos.
        assertThat(first.reassigned().size() + stopsOf(first.trips()).length).isEqualTo(first.orphans().length);
    }

    private static int[] stops(ShiftScheduler.Truck truck) {
        return Arrays.stream(truck.trips()).flatMap(k -> Arrays.stream(route(solution, k))).toArray();
    }

    private static int[] stopsOf(Solution trips) {
        List<Integer> all = new ArrayList<>();
        for (int k = 0; k < trips.routeCount(); k++) {
            for (int node : route(trips, k)) {
                all.add(node);
            }
        }
        return all.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] route(Solution s, int k) {
        int[] stops = new int[s.routeLength(k)];
        for (int p = 0; p < stops.length; p++) {
            stops[p] = s.customer(k, p);
        }
        return stops;
    }
}
