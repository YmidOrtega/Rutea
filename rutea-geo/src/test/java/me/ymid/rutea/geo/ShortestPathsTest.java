package me.ymid.rutea.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import me.ymid.rutea.geo.ShortestPaths.Weight;

class ShortestPathsTest {

    /**
     * 0 → 1 → 2 es corto en metros y lento; 0 → 2 directo es largo y rápido. 2 → 0 es de un solo sentido
     * y más largo que la ida: la matriz no es simétrica.
     */
    private static final RoadGraph TRIANGLE = TestGraphs.of(4,
            new int[] {0, 1, 100, 60},
            new int[] {1, 2, 100, 60},
            new int[] {0, 2, 300, 30},
            new int[] {2, 0, 500, 50});

    @Test
    void findsShortestDistanceByTheChosenWeight() {
        ShortestPaths meters = new ShortestPaths(TRIANGLE, Weight.METERS);
        ShortestPaths seconds = new ShortestPaths(TRIANGLE, Weight.SECONDS);

        meters.run(0);
        seconds.run(0);

        assertThat(meters.distance(2)).isEqualTo(200);
        assertThat(seconds.distance(2)).isEqualTo(30);
    }

    @Test
    void respectsOneWayStreets() {
        ShortestPaths paths = new ShortestPaths(TRIANGLE, Weight.METERS);

        assertThat(paths.distances(0, new int[] {2})).containsExactly(200);
        assertThat(paths.distances(2, new int[] {0, 1})).containsExactly(500, 600);
    }

    @Test
    void marksUnreachableVertices() {
        ShortestPaths paths = new ShortestPaths(TRIANGLE, Weight.METERS);

        assertThat(paths.distances(0, new int[] {3})).containsExactly(ShortestPaths.UNREACHABLE);
    }

    @Test
    void reconstructsThePathEdgeByEdge() {
        ShortestPaths paths = new ShortestPaths(TRIANGLE, Weight.METERS);
        paths.run(0);

        int[] edges = paths.pathEdges(2);

        assertThat(edges).hasSize(2);
        assertThat(TRIANGLE.target(edges[0])).isEqualTo(1);
        assertThat(TRIANGLE.target(edges[1])).isEqualTo(2);
        assertThat(paths.pathEdges(0)).isEmpty();
        assertThatThrownBy(() -> paths.pathEdges(3)).hasMessageContaining("no se alcanza");
    }

    @Test
    void reusedInstanceGivesTheSameAnswersAsAFreshOne() {
        RoadGraph grid = randomGraph(300, 1500, 42);
        ShortestPaths reused = new ShortestPaths(grid, Weight.METERS);
        int[] all = new int[grid.vertexCount()];
        for (int v = 0; v < all.length; v++) {
            all[v] = v;
        }

        for (int source = 0; source < grid.vertexCount(); source += 7) {
            int[] early = reused.distances(source, new int[] {(source * 31) % 300, (source * 17) % 300});
            int[] full = new ShortestPaths(grid, Weight.METERS).distances(source, all);

            assertThat(early).containsExactly(full[(source * 31) % 300], full[(source * 17) % 300]);
            assertThat(full).isEqualTo(bellmanFord(grid, source));
        }
    }

    /** Referencia lenta pero obvia para comparar. */
    private static int[] bellmanFord(RoadGraph graph, int source) {
        int n = graph.vertexCount();
        long[] distance = new long[n];
        Arrays.fill(distance, Long.MAX_VALUE);
        distance[source] = 0;
        for (int round = 0; round < n; round++) {
            boolean changed = false;
            for (int v = 0; v < n; v++) {
                if (distance[v] == Long.MAX_VALUE) {
                    continue;
                }
                for (int e = graph.firstEdge(v); e < graph.endEdge(v); e++) {
                    long candidate = distance[v] + graph.meters(e);
                    if (candidate < distance[graph.target(e)]) {
                        distance[graph.target(e)] = candidate;
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        int[] result = new int[n];
        for (int v = 0; v < n; v++) {
            result[v] = distance[v] == Long.MAX_VALUE ? ShortestPaths.UNREACHABLE : (int) distance[v];
        }
        return result;
    }

    private static RoadGraph randomGraph(int vertices, int edges, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        int[][] list = new int[edges][];
        for (int i = 0; i < edges; i++) {
            list[i] = new int[] {random.nextInt(vertices), random.nextInt(vertices), random.nextInt(1, 1000), 1};
        }
        return TestGraphs.of(vertices, list);
    }
}
