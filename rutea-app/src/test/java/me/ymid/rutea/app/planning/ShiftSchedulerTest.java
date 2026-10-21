package me.ymid.rutea.app.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.planning.ShiftScheduler.Schedule;
import me.ymid.rutea.app.planning.ShiftScheduler.Truck;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.app.scenario.SubaScenarioFixture;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

class ShiftSchedulerTest {

    private static final int SERVICE = 60;
    private static final int UNLOAD = 2_400;
    private static final int SHIFT = 8 * 3_600;

    @TempDir
    static Path dir;

    private static SubaScenario scenario;
    private static Solution solution;

    @BeforeAll
    static void plan() throws IOException {
        scenario = SubaScenarioFixture.town(dir, 20, 1_600);
        solution = new AlnsSolver().solve(scenario.instance(), new SolverSettings(3, Duration.ofMinutes(1), 200));
    }

    @Test
    void oneTripPerTruckGivesOneTruckPerTrip() {
        Schedule schedule = ShiftScheduler.schedule(scenario, solution, SERVICE, UNLOAD, SHIFT, 1);

        assertThat(schedule.trucks()).hasSize(solution.routeCount())
                .allSatisfy(truck -> assertThat(truck.trips()).hasSize(1));
    }

    @Test
    void chainingNeverNeedsMoreTrucksAndCoversEveryTripOnce() {
        Schedule single = ShiftScheduler.schedule(scenario, solution, SERVICE, UNLOAD, SHIFT, 1);
        Schedule chained = ShiftScheduler.schedule(scenario, solution, SERVICE, UNLOAD, SHIFT, 3);

        assertThat(chained.trucks().size()).isLessThanOrEqualTo(single.trucks().size());
        int[] covered = chained.trucks().stream().flatMapToInt(truck -> Arrays.stream(truck.trips())).sorted().toArray();
        assertThat(covered).isEqualTo(IntStream.range(0, solution.routeCount()).toArray());
        assertThat(chained.trucks()).filteredOn(truck -> truck.trips().length > 1)
                .allSatisfy(truck -> assertThat(truck.seconds()).isLessThanOrEqualTo(SHIFT));
    }

    @Test
    void chainsTripsWhenTheShiftAllowsIt() {
        // En el pueblo de prueba los viajes duran minutos: caben tres por camión.
        Schedule chained = ShiftScheduler.schedule(scenario, solution, SERVICE, UNLOAD, SHIFT, 3);

        assertThat(chained.trucks().size()).isEqualTo((solution.routeCount() + 2) / 3);
    }

    @Test
    void countsDrivingStopsUnloadingAndTheReturnToBase() {
        Schedule schedule = ShiftScheduler.schedule(scenario, solution, SERVICE, UNLOAD, SHIFT, 1);
        Truck truck = schedule.trucks().getFirst();
        int k = truck.trips()[0];
        TravelMatrix travel = scenario.travel();

        long meters = 0;
        long seconds = (long) SERVICE * solution.routeLength(k) + UNLOAD;
        int previous = 0;
        for (int p = 0; p < solution.routeLength(k); p++) {
            int stop = solution.customer(k, p);
            meters += travel.meters(previous, stop);
            seconds += travel.seconds(previous, stop);
            previous = stop;
        }
        meters += travel.meters(previous, scenario.gate()) + travel.meters(scenario.gate(), 0);
        seconds += travel.seconds(previous, scenario.gate()) + travel.seconds(scenario.gate(), 0);

        assertThat(truck.meters()).isEqualTo(meters);
        assertThat(truck.seconds()).isEqualTo(seconds);
    }

    @Test
    void marksTripsThatDoNotFitEvenAloneAsOvertime() {
        Schedule tight = ShiftScheduler.schedule(scenario, solution, SERVICE, UNLOAD, 60, 3);

        assertThat(tight.trucks()).hasSize(solution.routeCount());
        assertThat(tight.overtimeTrucks()).isEqualTo(solution.routeCount());
    }
}
