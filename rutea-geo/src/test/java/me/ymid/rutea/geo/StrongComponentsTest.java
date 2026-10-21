package me.ymid.rutea.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StrongComponentsTest {

    @Test
    void keepsTheLargestCycleAndDropsOneWayTails() {
        // Ciclo 1-2-3-4, cola de entrada 0 → 1 y salida sin regreso 4 → 5; 6 ⇄ 7 es otra isla.
        RoadGraph graph = TestGraphs.of(8,
                new int[] {0, 1, 1, 1}, new int[] {1, 2, 1, 1}, new int[] {2, 3, 1, 1}, new int[] {3, 4, 1, 1},
                new int[] {4, 1, 1, 1}, new int[] {4, 5, 1, 1}, new int[] {6, 7, 1, 1}, new int[] {7, 6, 1, 1});

        assertThat(StrongComponents.largest(8, firstEdges(graph), targets(graph))).containsExactly(1, 2, 3, 4);
    }

    @Test
    void handlesAChainLongerThanTheCallStack() {
        // 500.000 vértices en un solo ciclo: con recursión, esto desborda la pila.
        int n = 500_000;
        int[][] edges = new int[n][];
        for (int v = 0; v < n; v++) {
            edges[v] = new int[] {v, (v + 1) % n, 1, 1};
        }
        RoadGraph graph = TestGraphs.of(n, edges);

        assertThat(StrongComponents.largest(n, firstEdges(graph), targets(graph))).hasSize(n);
    }

    @Test
    void breaksTiesTowardTheComponentWithTheSmallestVertex() {
        RoadGraph graph = TestGraphs.of(4,
                new int[] {2, 3, 1, 1}, new int[] {3, 2, 1, 1}, new int[] {0, 1, 1, 1}, new int[] {1, 0, 1, 1});

        assertThat(StrongComponents.largest(4, firstEdges(graph), targets(graph))).containsExactly(0, 1);
    }

    private static int[] firstEdges(RoadGraph graph) {
        int[] first = new int[graph.vertexCount() + 1];
        for (int v = 0; v <= graph.vertexCount(); v++) {
            first[v] = v < graph.vertexCount() ? graph.firstEdge(v) : graph.edgeCount();
        }
        return first;
    }

    private static int[] targets(RoadGraph graph) {
        int[] targets = new int[graph.edgeCount()];
        for (int e = 0; e < targets.length; e++) {
            targets[e] = graph.target(e);
        }
        return targets;
    }
}
