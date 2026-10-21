package me.ymid.rutea.app.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.operations.LiveOperation.Dispatch;
import me.ymid.rutea.app.operations.LiveOperation.Pickup;
import me.ymid.rutea.app.planning.DemoPlan;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.planning.SubaPlanner;
import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.app.scenario.SubaScenarioFixture;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

class LiveOperationTest {

    @TempDir
    static Path dir;

    private static SubaScenario scenario;
    private static Solution solution;
    private static ShiftScheduler.Schedule schedule;

    @BeforeAll
    static void plan() throws IOException {
        // Cargas chicas: los viajes quedan con cupo para recogidas extra.
        scenario = SubaScenarioFixture.town(dir, 24, 400);
        solution = new AlnsSolver().solve(scenario.instance(), new SolverSettings(4, Duration.ofMinutes(1), 200));
        schedule = ShiftScheduler.schedule(scenario, solution, SubaFigures.SERVICE_SECONDS_PER_CONTAINER,
                SubaFigures.UNLOAD_SECONDS_LOW, SubaFigures.SHIFT_SECONDS, 1);
    }

    private static LiveOperation fresh() {
        return new LiveOperation(scenario, solution, schedule, SubaFigures.SERVICE_SECONDS_PER_CONTAINER);
    }

    @Test
    void anOverflowBeforeItsPlannedVisitIsLeftToThatVisitWhenNoDetourArrivesSooner() {
        LiveOperation operation = fresh();
        ShiftScheduler.Visit first = firstVisit(0);

        Dispatch dispatch = operation.overflow(first.node(), 0);

        assertThat(dispatch.pickup()).isEqualTo(Pickup.PLANNED);
        assertThat(dispatch.truck()).isZero();
        assertThat(dispatch.pickupSeconds()).isEqualTo(first.arriveSeconds());
        assertThat(dispatch.extraMeters()).isZero();
        assertThat(operation.meters(0)).isEqualTo(schedule.trucks().getFirst().meters());
    }

    @Test
    void anOverflowAfterItsVisitGetsAnExtraPickupAndKeepsEveryPlannedVisit() {
        LiveOperation operation = fresh();
        ShiftScheduler.Visit first = firstVisit(0);
        long after = first.arriveSeconds() + SubaFigures.SERVICE_SECONDS_PER_CONTAINER;
        long before = totalMeters(operation);

        Dispatch dispatch = operation.overflow(first.node(), after);

        assertThat(dispatch.pickup()).isEqualTo(Pickup.IN_SERVICE);
        assertThat(dispatch.pickupSeconds()).isGreaterThanOrEqualTo(after);
        assertThat(totalMeters(operation) - before).isEqualTo(dispatch.extraMeters());
        int t = dispatch.truck();
        assertThat(operation.endSeconds(t)).isLessThanOrEqualTo(operation.shiftSeconds());
        for (int[] trip : operation.trips(t)) {
            assertThat(Arrays.stream(trip).map(scenario.instance()::demand).sum())
                    .isLessThanOrEqualTo(scenario.instance().capacity());
        }
        assertThat(operation.plannedArrivals(t).keySet()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(stops(schedule.trucks().get(t))).boxed().toList());
    }

    @Test
    void theChangedShiftDrawsOnTheStreetsWithTheSameMetersAndSeconds() {
        LiveOperation operation = fresh();
        ShiftScheduler.Visit first = firstVisit(0);
        Dispatch dispatch = operation.overflow(first.node(),
                first.arriveSeconds() + SubaFigures.SERVICE_SECONDS_PER_CONTAINER);
        int t = dispatch.truck();

        // redraw falla si la geometría no mide lo mismo que el plan vivo.
        DemoPlan.Route route = SubaPlanner.redraw(scenario, operation.trips(t), operation.meters(t),
                operation.endSeconds(t), operation.unloadSeconds(), "C-01");

        assertThat(route.distanceMeters()).isEqualTo(operation.meters(t));
        assertThat(route.stops()).filteredOn(scenario.containers().get(first.node() - 1).id()::equals).hasSize(2);
    }

    @Test
    void whenEveryShiftIsOverAReserveLeavesTheBaseAfterTheDispatchDelay() {
        LiveOperation operation = fresh();
        long late = schedule.trucks().stream().mapToLong(ShiftScheduler.Truck::seconds).max().orElseThrow();
        int node = firstVisit(0).node();

        Dispatch dispatch = operation.overflow(node, late);

        assertThat(dispatch.pickup()).isEqualTo(Pickup.RESERVE);
        assertThat(dispatch.pickupSeconds()).isEqualTo(
                late + SubaFigures.RESERVE_DISPATCH_SECONDS + scenario.travel().seconds(0, node));
        assertThat(dispatch.extraMeters()).isEqualTo(scenario.travel().meters(0, node)
                + scenario.travel().meters(node, scenario.gate()) + scenario.returnMeters());
    }

    @Test
    void aBreakdownHandsItsPendingExtraPickupsToAnotherTruck() {
        LiveOperation operation = fresh();
        ShiftScheduler.Visit first = firstVisit(0);
        long after = first.arriveSeconds() + SubaFigures.SERVICE_SECONDS_PER_CONTAINER;
        Dispatch extra = operation.overflow(first.node(), after);
        long meters = operation.meters(extra.truck());

        List<Dispatch> again = operation.outOfService(extra.truck(), after);

        assertThat(again).singleElement().satisfies(d -> {
            assertThat(d.node()).isEqualTo(first.node());
            assertThat(d.truck()).isNotEqualTo(extra.truck());
        });
        assertThat(operation.meters(extra.truck())).isEqualTo(meters - extra.extraMeters());
        assertThat(operation.outOfService(extra.truck(), after)).isEmpty();
    }

    private static ShiftScheduler.Visit firstVisit(int truck) {
        return ShiftScheduler.timeline(scenario, solution, schedule.trucks().get(truck),
                SubaFigures.SERVICE_SECONDS_PER_CONTAINER, schedule.unloadSeconds(), 0).getFirst();
    }

    private static long totalMeters(LiveOperation operation) {
        long total = 0;
        for (int t = 0; t < schedule.trucks().size(); t++) {
            total += operation.meters(t);
        }
        return total;
    }

    private static int[] stops(ShiftScheduler.Truck truck) {
        return Arrays.stream(truck.trips()).flatMap(k -> Arrays.stream(solution.route(k)))
                .toArray();
    }
}
