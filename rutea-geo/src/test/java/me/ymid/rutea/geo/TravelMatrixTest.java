package me.ymid.rutea.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
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

    @Test
    void repairAfterEachClosureEqualsComputingFromScratch() {
        RoadGraph town = twoWayTown(300, 500, 11);
        int[] points = new SplittableRandom(4).ints(60, 0, town.vertexCount()).toArray();
        TravelMatrix matrix = TravelMatrix.compute(town, points, 2);
        SplittableRandom random = new SplittableRandom(9);

        for (int round = 0; round < 25; round++) {
            int[] shapes = random.ints(1 + random.nextInt(3), 0, town.shapeCount()).toArray();
            TravelMatrix.Repair repair = matrix.closing(shapes, 1 + round % 4);
            matrix = repair.matrix();

            assertSameAsDijkstra(matrix, points);
            TravelMatrix scratch = TravelMatrix.computeAllowingUnreachable(matrix.graph(), points, 2);
            for (int i = 0; i < points.length; i++) {
                for (int j = 0; j < points.length; j++) {
                    assertThat(matrix.seconds(i, j)).isEqualTo(scratch.seconds(i, j));
                }
            }
            assertThat(matrix.metersRows()).isDeepEqualTo(scratch.metersRows());
            assertThat(repair.changedPairs()).isLessThanOrEqualTo(repair.affectedPairs());
        }
    }

    @Test
    void aClosureOffEveryShortestPathTouchesNothing() {
        // 0 ─ 1 ─ 2 corto y un rodeo 0 ─ 3 ─ 2 largo: cerrar el rodeo no cambia ninguna celda.
        RoadGraph graph = TestGraphs.streets(4, new int[] {0, 1, 100, 10}, new int[] {1, 2, 100, 10},
                new int[] {0, 3, 500, 50}, new int[] {3, 2, 500, 50});
        TravelMatrix matrix = TravelMatrix.compute(graph, new int[] {0, 1, 2}, 1);

        TravelMatrix.Repair repair = matrix.closing(new int[] {2}, 1);

        assertThat(repair.affectedPairs()).isZero();
        assertThat(repair.rows() + repair.columns()).isZero();
        assertThat(repair.matrix().metersRows()).isDeepEqualTo(matrix.metersRows());
    }

    @Test
    void closingTheStreetToOnePointRecomputesItsRowAndColumnNotTheWholeMatrix() {
        RoadGraph grid = grid(12);
        int[] points = new SplittableRandom(2).ints(80, 1, grid.vertexCount()).toArray();
        points[0] = 0;
        TravelMatrix matrix = TravelMatrix.compute(grid, points, 2);
        int street = grid.shape(grid.firstEdge(0));

        TravelMatrix.Repair repair = matrix.closing(new int[] {street}, 2);

        assertThat(repair.changedPairs()).isGreaterThan(points.length / 2);
        assertThat(repair.rows() + repair.columns()).isLessThanOrEqualTo(6);
        assertSameAsDijkstra(repair.matrix(), points);
    }

    @Test
    void closingTheOnlyWayInLeavesThePointUnreachable() {
        RoadGraph graph = TestGraphs.streets(3, new int[] {0, 1, 100, 10}, new int[] {1, 2, 100, 10});
        TravelMatrix matrix = TravelMatrix.compute(graph, new int[] {0, 1, 2}, 1);

        TravelMatrix repaired = matrix.closing(new int[] {1}, 1).matrix();

        assertThat(repaired.reachable(0, 1)).isTrue();
        assertThat(repaired.reachable(0, 2)).isFalse();
        assertThat(repaired.reachable(2, 0)).isFalse();
        assertThat(repaired.meters(2, 1)).isEqualTo(TravelMatrix.UNREACHABLE);
        assertThat(repaired.seconds(1, 2)).isEqualTo(TravelMatrix.UNREACHABLE);
        assertThat(repaired.graph().edgeCount()).isEqualTo(2);
    }

    private static void assertSameAsDijkstra(TravelMatrix matrix, int[] points) {
        ShortestPaths paths = new ShortestPaths(matrix.graph(), Weight.METERS);
        for (int i = 0; i < points.length; i++) {
            paths.run(points[i]);
            for (int j = 0; j < points.length; j++) {
                int expected = paths.distance(points[j]);
                assertThat(matrix.meters(i, j)).as("metros de %d a %d", i, j).isEqualTo(expected);
                assertThat(matrix.seconds(i, j)).as("segundos de %d a %d", i, j)
                        .isEqualTo(expected == ShortestPaths.UNREACHABLE ? TravelMatrix.UNREACHABLE
                                : paths.secondary(points[j]));
            }
        }
    }

    private static RoadGraph grid(int side) {
        List<int[]> streets = new ArrayList<>();
        SplittableRandom random = new SplittableRandom(1);
        for (int r = 0; r < side; r++) {
            for (int c = 0; c < side; c++) {
                int v = r * side + c;
                if (c + 1 < side) {
                    streets.add(new int[] {v, v + 1, 100, random.nextInt(8, 20)});
                }
                if (r + 1 < side) {
                    streets.add(new int[] {v, v + side, 100, random.nextInt(8, 20)});
                }
            }
        }
        return TestGraphs.streets(side * side, streets.toArray(int[][]::new));
    }

    private static RoadGraph twoWayTown(int vertices, int extra, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        int[][] streets = new int[vertices - 1 + extra][];
        for (int v = 0; v + 1 < vertices; v++) {
            streets[v] = new int[] {v, v + 1, random.nextInt(20, 300), random.nextInt(2, 40)};
        }
        for (int i = 0; i < extra; i++) {
            // Metros repetidos: muchos empates que desempatan los segundos.
            streets[vertices - 1 + i] = new int[] {random.nextInt(vertices), random.nextInt(vertices),
                    random.nextInt(1, 6) * 100, random.nextInt(2, 40)};
        }
        return TestGraphs.streets(vertices, streets);
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
