package me.ymid.rutea.app.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class DemoPlannerTest {

    private final DemoPlan plan = DemoPlanner.plan(DemoPlanExporter.DEFAULT_SEED);

    @Test
    void sameSeedGivesSamePlan() {
        DemoPlan again = DemoPlanner.plan(DemoPlanExporter.DEFAULT_SEED);

        assertThat(again.containers()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(plan.containers());
        assertThat(again.routes()).extracting(DemoPlan.Route::stops)
                .containsExactlyElementsOf(plan.routes().stream().map(DemoPlan.Route::stops).toList());
    }

    @Test
    void everyContainerIsCollectedOnceByARouteOfItsOwnStream() {
        Map<String, DemoPlan.Container> byId = plan.containers().stream()
                .collect(Collectors.toMap(DemoPlan.Container::id, Function.identity()));
        List<String> collected = plan.routes().stream().flatMap(route -> route.stops().stream()).toList();

        assertThat(collected).hasSize(DemoPlanner.CONTAINERS).doesNotHaveDuplicates();
        assertThat(plan.routes()).allSatisfy(route -> assertThat(route.stops()).allSatisfy(stop -> {
            assertThat(byId.get(stop).stream()).isEqualTo(route.stream());
            assertThat(byId.get(stop).route()).isEqualTo(route.id());
        }));
    }

    @Test
    void routesRespectVehicleCapacity() {
        assertThat(plan.routes()).allSatisfy(route -> {
            assertThat(route.loadKg()).isPositive().isLessThanOrEqualTo(route.capacityKg());
            assertThat(route.distanceMeters()).isPositive();
        });
    }

    @Test
    void pathsStartAndEndAtDepotAndOnlyMoveAlongTheGrid() {
        assertThat(plan.routes()).allSatisfy(route -> {
            List<double[]> path = route.path();
            assertThat(path.getFirst()).containsExactly(plan.depot());
            assertThat(path.getLast()).containsExactly(plan.depot());
            for (int i = 1; i < path.size(); i++) {
                boolean sameLon = path.get(i)[0] == path.get(i - 1)[0];
                boolean sameLat = path.get(i)[1] == path.get(i - 1)[1];
                assertThat(sameLon || sameLat).as("tramo %d de %s en diagonal", i, route.id()).isTrue();
            }
        });
    }

    @Test
    void saysItIsSimulated() {
        assertThat(plan.meta().simulated()).isTrue();
        assertThat(plan.meta().solver()).isEqualTo("nearest-neighbor");
    }
}
