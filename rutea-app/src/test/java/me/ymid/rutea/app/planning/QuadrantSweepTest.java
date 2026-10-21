package me.ymid.rutea.app.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.app.scenario.SubaScenarioFixture;
import me.ymid.rutea.geo.geometry.Haversine;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Solution;

class QuadrantSweepTest {

    @TempDir
    static Path dir;

    private static SubaScenario scenario;

    @BeforeAll
    static void town() throws IOException {
        scenario = SubaScenarioFixture.town(dir, 25, 1_600);
    }

    @Test
    void collectsEveryContainerOnceWithinCapacityForEveryCellSize() {
        for (int cell : new int[] {200, 400, 1_000, 5_000}) {
            Solution sweep = QuadrantSweep.solve(scenario, cell);

            assertThat(Evaluator.evaluate(scenario.instance(), sweep).violations()).as("%d m", cell).isEmpty();
        }
    }

    @Test
    void closesATripOnlyWhenTheNextContainerNoLongerFits() {
        Solution sweep = QuadrantSweep.solve(scenario, 1_000);

        for (int k = 0; k + 1 < sweep.routeCount(); k++) {
            int load = 0;
            for (int p = 0; p < sweep.routeLength(k); p++) {
                load += scenario.instance().demand(sweep.customer(k, p));
            }
            int next = scenario.instance().demand(sweep.customer(k + 1, 0));
            assertThat(load + next).as("viaje %d", k).isGreaterThan(scenario.instance().capacity());
        }
    }

    @Test
    void sweepsTheFirstLaneFromWestToEastStartingInTheNorth() {
        // Un solo cuadrante grande: la primera franja es la más al norte y va de oeste a este.
        List<Container> visited = visitOrder(QuadrantSweep.solve(scenario, 50_000));
        int northernmost = scenario.containers().stream().mapToInt(Container::latE7).max().orElseThrow();
        List<Container> firstLane = visited.stream()
                .takeWhile(c -> northernmost - c.latE7() < Haversine.latitudeE7(QuadrantSweep.LANE_METERS))
                .toList();

        assertThat(firstLane).isNotEmpty();
        assertThat(firstLane).extracting(Container::lonE7).isSorted();
    }

    @Test
    void refusesQuadrantsNarrowerThanALane() {
        assertThatThrownBy(() -> QuadrantSweep.solve(scenario, 100)).hasMessageContaining("al menos una franja");
    }

    private static List<Container> visitOrder(Solution solution) {
        List<Container> order = new ArrayList<>();
        for (int k = 0; k < solution.routeCount(); k++) {
            for (int p = 0; p < solution.routeLength(k); p++) {
                order.add(scenario.containers().get(solution.customer(k, p) - 1));
            }
        }
        return order;
    }
}
