package me.ymid.rutea.app.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.app.scenario.SubaScenarioFixture;
import me.ymid.rutea.geo.geometry.Haversine;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

class SubaPlannerTest {

    private static final int CONTAINERS = 20;

    @TempDir
    static Path dir;

    private static SubaScenario scenario;
    private static DemoPlan plan;

    @BeforeAll
    static void planTown() throws IOException {
        // Cargas grandes para que el pueblo de prueba necesite varios viajes de 8 t.
        scenario = SubaScenarioFixture.town(dir, CONTAINERS, 1_600);
        plan = plan(3);
    }

    private static DemoPlan plan(long seed) {
        return SubaPlanner.plan(scenario, new AlnsSolver(), new SolverSettings(seed, Duration.ofMinutes(1), 200));
    }

    @Test
    void collectsEveryContainerOnceAndRecordsItsRoute() {
        Map<String, DemoPlan.Container> byId = plan.containers().stream()
                .collect(Collectors.toMap(DemoPlan.Container::id, Function.identity()));
        List<String> collected = plan.routes().stream().flatMap(route -> route.stops().stream()).toList();

        assertThat(collected).hasSize(CONTAINERS).doesNotHaveDuplicates();
        assertThat(plan.routes()).allSatisfy(route -> assertThat(route.stops())
                .allSatisfy(stop -> assertThat(byId.get(stop).route()).isEqualTo(route.id())));
        assertThat(plan.routes()).hasSizeGreaterThan(1);
    }

    @Test
    void everyShiftLeavesTheBaseUnloadsAtTheGateAndComesBack() {
        double[] gate = site("relleno");
        double[] base = site("base");

        assertThat(plan.routes()).allSatisfy(route -> {
            assertThat(route.path().getFirst()).containsExactly(base);
            assertThat(route.path().getLast()).containsExactly(base);
            assertThat(route.path()).anySatisfy(point -> assertThat(point).containsExactly(gate));
            assertThat(route.loadKg()).isPositive().isLessThanOrEqualTo(route.capacityKg());
        });
    }

    @Test
    void stopsAtEveryContainerForTheServiceTime() {
        assertThat(plan.routes()).allSatisfy(route -> {
            int stops = 0;
            for (int i = 1; i < route.path().size(); i++) {
                if (Arrays.equals(route.path().get(i), route.path().get(i - 1))
                        && route.times()[i] - route.times()[i - 1] == plan.meta().serviceSeconds()) {
                    stops++;
                }
            }
            assertThat(stops).as(route.id()).isEqualTo(route.stops().size());
        });
        assertThat(plan.meta().serviceSeconds()).isEqualTo(60);
    }

    private static double[] site(String id) {
        return plan.sites().stream().filter(site -> site.id().equals(id)).findFirst().orElseThrow().position();
    }

    @Test
    void drawnPathMeasuresWhatTheSolverOptimized() {
        assertThat(plan.routes()).allSatisfy(route -> {
            double meters = 0;
            for (int i = 1; i < route.path().size(); i++) {
                double[] a = route.path().get(i - 1);
                double[] b = route.path().get(i);
                meters += Haversine.meters(e7(a[1]), e7(a[0]), e7(b[1]), e7(b[0]));
            }
            // Las coordenadas van redondeadas a ~1 m y cada arco a metros enteros: el largo se desvía apenas.
            assertThat(meters).as(route.id()).isCloseTo(route.distanceMeters(), offset(2.0 * route.path().size()));
        });
    }

    @Test
    void timesGrowAlongThePathUpToTheTripDuration() {
        assertThat(plan.routes()).allSatisfy(route -> {
            assertThat(route.times()).hasSize(route.path().size()).isSorted();
            assertThat(route.times()[0]).isZero();
            assertThat(route.times()[route.times().length - 1]).isEqualTo(route.durationSeconds()).isPositive();
        });
    }

    @Test
    void theSameSeedGivesTheSamePlan() {
        DemoPlan again = plan(3);

        assertThat(again.routes()).extracting(DemoPlan.Route::stops)
                .containsExactlyElementsOf(plan.routes().stream().map(DemoPlan.Route::stops).toList());
    }

    @Test
    void saysWhatIsRealAndWhatIsSimulated() {
        assertThat(plan.meta().simulated()).isTrue();
        assertThat(plan.meta().network()).contains("Calles reales").contains("simulados");
        assertThat(plan.meta().attribution()).contains("OpenStreetMap").contains("ODbL");
        assertThat(plan.meta().iterations()).isEqualTo(200);
        assertThat(plan.sites()).extracting(DemoPlan.Site::id).containsExactly("relleno", "base");
    }

    private static int e7(double degrees) {
        return (int) Math.round(degrees * 1e7);
    }
}
