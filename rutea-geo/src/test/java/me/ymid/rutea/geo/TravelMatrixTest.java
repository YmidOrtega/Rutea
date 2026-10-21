package me.ymid.rutea.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import me.ymid.rutea.geo.ShortestPaths.Weight;

class TravelMatrixTest {

    /** Un anillo dirigido (para que sea fuertemente conexo) más atajos aleatorios en ambos sentidos. */
    private static final RoadGraph CITY = stronglyConnected(400, 2000, 7);

    @Test
    void everyCellIsTheShortestPathBetweenThePoints() {
        int[] points = {3, 150, 7, 399, 0, 222};

        TravelMatrix matrix = TravelMatrix.compute(CITY, points, 3);

        ShortestPaths meters = new ShortestPaths(CITY, Weight.METERS);
        for (int i = 0; i < points.length; i++) {
            meters.run(points[i]);
            for (int j = 0; j < points.length; j++) {
                assertThat(matrix.meters(i, j)).isEqualTo(meters.distance(points[j]));
                // Los segundos son los del mismo camino más corto, sumados arco por arco.
                int along = 0;
                for (int e : meters.pathEdges(points[j])) {
                    along += CITY.seconds(e);
                }
                assertThat(matrix.seconds(i, j)).isEqualTo(along);
            }
            assertThat(matrix.meters(i, i)).isZero();
        }
    }

    @Test
    void sameResultWhateverTheThreadCount() {
        int[] points = new SplittableRandom(3).ints(120, 0, CITY.vertexCount()).toArray();

        TravelMatrix one = TravelMatrix.compute(CITY, points, 1);
        TravelMatrix many = TravelMatrix.compute(CITY, points, 8);

        assertThat(many.metersRows()).isDeepEqualTo(one.metersRows());
        for (int i = 0; i < points.length; i++) {
            for (int j = 0; j < points.length; j++) {
                assertThat(many.seconds(i, j)).isEqualTo(one.seconds(i, j));
            }
        }
    }

    @Test
    void allowsTwoPointsOnTheSameVertex() {
        TravelMatrix matrix = TravelMatrix.compute(CITY, new int[] {5, 5, 9}, 2);

        assertThat(matrix.meters(0, 1)).isZero();
        assertThat(matrix.meters(0, 2)).isEqualTo(matrix.meters(1, 2));
    }

    @Test
    void isAsymmetricOnOneWayStreets() {
        RoadGraph oneWayLoop = TestGraphs.of(3,
                new int[] {0, 1, 100, 10}, new int[] {1, 2, 100, 10}, new int[] {2, 0, 100, 10});

        TravelMatrix matrix = TravelMatrix.compute(oneWayLoop, new int[] {0, 1}, 1);

        assertThat(matrix.meters(0, 1)).isEqualTo(100);
        assertThat(matrix.meters(1, 0)).isEqualTo(200);
    }

    @Test
    void refusesAGraphThatIsNotStronglyConnected() {
        RoadGraph deadEnd = TestGraphs.of(2, new int[] {0, 1, 100, 10});

        assertThatThrownBy(() -> TravelMatrix.compute(deadEnd, new int[] {0, 1}, 1))
                .hasMessageContaining("no hay camino de 1 a 0");
    }

    private static RoadGraph stronglyConnected(int vertices, int shortcuts, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        int[][] edges = new int[vertices + shortcuts][];
        for (int v = 0; v < vertices; v++) {
            edges[v] = new int[] {v, (v + 1) % vertices, random.nextInt(50, 500), random.nextInt(5, 60)};
        }
        for (int i = 0; i < shortcuts; i++) {
            edges[vertices + i] = new int[] {random.nextInt(vertices), random.nextInt(vertices),
                    random.nextInt(50, 5000), random.nextInt(5, 600)};
        }
        return TestGraphs.of(vertices, edges);
    }
}
