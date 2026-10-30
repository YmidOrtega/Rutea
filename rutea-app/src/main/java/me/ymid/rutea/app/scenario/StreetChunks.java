package me.ymid.rutea.app.scenario;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;

import me.ymid.rutea.geo.RoadGraph;

/**
 * Las calles locales de un área, agrupadas en trozos para el puerta a puerta (ADR 0014). Cada trozo es una caminata
 * por arcos del grafo, en su sentido de circulación: el camión entra por el primer vértice, recoge a lado y lado y sale
 * por el último. Cada tramo de calle entra en un solo trozo, aunque sea de doble sentido.
 *
 * <p>Un trozo crece siguiendo la calle que menos gira, primero hacia adelante y, cuando ya no puede, hacia atrás
 * desde su entrada. Un arco entra solo si el trozo no pasa de {@code maxMeters}; solo un tramo que ya mide más queda
 * como trozo propio. Con 0, cada tramo es su propio trozo. Determinista: recorre los arcos en orden.
 */
public final class StreetChunks {

    /**
     * @param arcs arcos del grafo en el orden en que se recorren
     * @param entry vértice por el que se entra
     * @param exit vértice por el que se sale
     * @param meters lo que mide el recorrido
     */
    public record Chunk(int[] arcs, int entry, int exit, int meters) {
    }

    private StreetChunks() {
    }

    public static List<Chunk> of(RoadGraph graph, IntPredicate area, int maxMeters) {
        if (maxMeters < 0) {
            throw new IllegalArgumentException("maxMeters no puede ser negativo: " + maxMeters);
        }
        Incoming incoming = Incoming.of(graph);
        boolean[] taken = new boolean[graph.shapeCount()];
        List<Chunk> chunks = new ArrayList<>();
        int[] ahead = new int[16];
        int[] behind = new int[16];
        for (int first = 0; first < graph.edgeCount(); first++) {
            if (!eligible(graph, area, taken, first)) {
                continue;
            }
            taken[base(graph.shape(first))] = true;
            int meters = graph.meters(first);
            int forward = 0;
            int backward = 0;
            int last = first;
            int head = first;
            while (true) {
                int next = straightestOut(graph, area, taken, last);
                if (next < 0 || meters + graph.meters(next) > maxMeters) {
                    break;
                }
                taken[base(graph.shape(next))] = true;
                meters += graph.meters(next);
                ahead = append(ahead, forward++, next);
                last = next;
            }
            while (true) {
                int previous = straightestIn(graph, incoming, area, taken, head);
                if (previous < 0 || meters + graph.meters(previous) > maxMeters) {
                    break;
                }
                taken[base(graph.shape(previous))] = true;
                meters += graph.meters(previous);
                behind = append(behind, backward++, previous);
                head = previous;
            }
            int[] arcs = new int[backward + 1 + forward];
            for (int k = 0; k < backward; k++) {
                arcs[k] = behind[backward - 1 - k];
            }
            arcs[backward] = first;
            System.arraycopy(ahead, 0, arcs, backward + 1, forward);
            chunks.add(new Chunk(arcs, graph.source(head), graph.target(last), meters));
        }
        return chunks;
    }

    /** El arco libre que sale del final de {@code arc} con el menor giro; -1 si no hay. */
    private static int straightestOut(RoadGraph graph, IntPredicate area, boolean[] taken, int arc) {
        int vertex = graph.target(arc);
        double heading = heading(graph, arc);
        int best = -1;
        double bestTurn = Double.MAX_VALUE;
        for (int e = graph.firstEdge(vertex); e < graph.endEdge(vertex); e++) {
            if (eligible(graph, area, taken, e)) {
                double turn = turn(heading, heading(graph, e));
                if (turn < bestTurn) {
                    bestTurn = turn;
                    best = e;
                }
            }
        }
        return best;
    }

    /** El arco libre que llega al comienzo de {@code arc} con el menor giro; -1 si no hay. */
    private static int straightestIn(RoadGraph graph, Incoming incoming, IntPredicate area, boolean[] taken, int arc) {
        int vertex = graph.source(arc);
        double heading = heading(graph, arc);
        int best = -1;
        double bestTurn = Double.MAX_VALUE;
        for (int k = incoming.first[vertex]; k < incoming.first[vertex + 1]; k++) {
            int e = incoming.arcs[k];
            if (eligible(graph, area, taken, e)) {
                double turn = turn(heading(graph, e), heading);
                if (turn < bestTurn) {
                    bestTurn = turn;
                    best = e;
                }
            }
        }
        return best;
    }

    /** Los arcos que llegan a cada vértice, agrupados por vértice como los que salen en {@link RoadGraph}. */
    private record Incoming(int[] first, int[] arcs) {

        static Incoming of(RoadGraph graph) {
            int[] first = new int[graph.vertexCount() + 1];
            for (int e = 0; e < graph.edgeCount(); e++) {
                first[graph.target(e) + 1]++;
            }
            for (int v = 0; v < graph.vertexCount(); v++) {
                first[v + 1] += first[v];
            }
            int[] next = Arrays.copyOf(first, graph.vertexCount());
            int[] arcs = new int[graph.edgeCount()];
            for (int e = 0; e < graph.edgeCount(); e++) {
                arcs[next[graph.target(e)]++] = e;
            }
            return new Incoming(first, arcs);
        }
    }

    private static int[] append(int[] walk, int length, int arc) {
        int[] grown = length == walk.length ? Arrays.copyOf(walk, length * 2) : walk;
        grown[length] = arc;
        return grown;
    }

    private static double turn(double from, double to) {
        return Math.abs(Math.IEEEremainder(to - from, 2 * Math.PI));
    }

    private static boolean eligible(RoadGraph graph, IntPredicate area, boolean[] taken, int arc) {
        int shape = base(graph.shape(arc));
        return !taken[shape] && SimulatedContainers.LOCAL_STREETS.contains(graph.roadClass(arc)) && area.test(shape);
    }

    /** Rumbo de la recta entre los extremos del arco; los grados de longitud se acortan con la latitud. */
    private static double heading(RoadGraph graph, int arc) {
        int from = graph.source(arc);
        int to = graph.target(arc);
        double cosLat = Math.cos(Math.toRadians(graph.latE7(from) / 1e7));
        return Math.atan2((graph.lonE7(to) - graph.lonE7(from)) * cosLat, graph.latE7(to) - graph.latE7(from));
    }

    private static int base(int shape) {
        return shape >= 0 ? shape : ~shape;
    }
}
