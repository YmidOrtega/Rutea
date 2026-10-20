package me.ymid.rutea.geo;

import java.util.Arrays;

/**
 * Dijkstra sobre un {@link RoadGraph}. Cada instancia guarda sus arreglos de trabajo para reutilizarlos
 * entre búsquedas, sin reservar memoria en cada una: se usa una por hilo.
 *
 * <p>El montículo guarda {@code long} que empaquetan la distancia (32 bits altos) y el vértice (32 bits
 * bajos), así ordenar por el {@code long} es ordenar por distancia, sin objetos ni boxing.
 */
public final class ShortestPaths {

    public enum Weight { METERS, SECONDS }

    public static final int UNREACHABLE = Integer.MAX_VALUE;

    private final RoadGraph graph;
    private final Weight weight;
    private final int[] distance;
    private final int[] parentEdge;
    private final int[] touched;
    private final int[] targetMark;
    private int mark;
    private int touchedCount;
    private long[] heap;
    private int heapSize;
    private int source = -1;

    public ShortestPaths(RoadGraph graph, Weight weight) {
        this.graph = graph;
        this.weight = weight;
        int n = graph.vertexCount();
        distance = new int[n];
        parentEdge = new int[n];
        touched = new int[n];
        targetMark = new int[n];
        Arrays.fill(distance, UNREACHABLE);
        Arrays.fill(parentEdge, -1);
        heap = new long[Math.max(16, n / 4)];
    }

    /** Distancias desde {@code source} a todos los vértices; {@link #UNREACHABLE} si no se llega. */
    public void run(int source) {
        runUntil(source, null);
    }

    /**
     * Como {@link #run}, pero se detiene cuando ya fijó la distancia de todos los {@code targets}. Devuelve
     * las distancias en el orden de {@code targets}.
     */
    public int[] distances(int source, int[] targets) {
        int[] result = new int[targets.length];
        distances(source, targets, result);
        return result;
    }

    /** Como {@link #distances(int, int[])}, escribiendo en {@code into}: para llenar una matriz fila por fila. */
    public void distances(int source, int[] targets, int[] into) {
        runUntil(source, targets);
        for (int i = 0; i < targets.length; i++) {
            into[i] = distance[targets[i]];
        }
    }

    public int distance(int vertex) {
        return distance[vertex];
    }

    /** Arcos del camino más corto desde la última fuente hasta {@code target}, en orden. */
    public int[] pathEdges(int target) {
        if (distance[target] == UNREACHABLE) {
            throw new IllegalArgumentException("el vértice " + target + " no se alcanza desde " + source);
        }
        int count = 0;
        for (int v = target; v != source; v = sourceOf(parentEdge[v])) {
            count++;
        }
        int[] edges = new int[count];
        for (int v = target, i = count - 1; v != source; v = sourceOf(parentEdge[v]), i--) {
            edges[i] = parentEdge[v];
        }
        return edges;
    }

    private int sourceOf(int edge) {
        // El CSR no guarda el origen de cada arco: se busca el vértice cuyo rango lo contiene.
        int lo = 0;
        int hi = graph.vertexCount() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (graph.firstEdge(mid) <= edge) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private void runUntil(int source, int[] targets) {
        reset();
        this.source = source;
        int pending = 0;
        if (targets != null) {
            // Cada búsqueda usa una marca nueva: no hay que limpiar targetMark entre búsquedas.
            if (++mark == Integer.MAX_VALUE) {
                Arrays.fill(targetMark, 0);
                mark = 1;
            }
            for (int t : targets) {
                if (targetMark[t] != mark) {
                    targetMark[t] = mark;
                    pending++;
                }
            }
            if (pending == 0) {
                return;
            }
        }
        setDistance(source, 0, -1);
        push(source, 0);
        while (heapSize > 0) {
            long top = pop();
            int d = (int) (top >>> 32);
            int v = (int) top;
            if (d > distance[v]) {
                continue;
            }
            if (targets != null && targetMark[v] == mark) {
                targetMark[v] = 0;
                if (--pending == 0) {
                    return;
                }
            }
            for (int e = graph.firstEdge(v); e < graph.endEdge(v); e++) {
                int w = graph.target(e);
                long candidate = (long) d + (weight == Weight.METERS ? graph.meters(e) : graph.seconds(e));
                if (candidate < distance[w]) {
                    setDistance(w, (int) candidate, e);
                    push(w, (int) candidate);
                }
            }
        }
    }

    private void setDistance(int vertex, int value, int edge) {
        if (distance[vertex] == UNREACHABLE) {
            touched[touchedCount++] = vertex;
        }
        distance[vertex] = value;
        parentEdge[vertex] = edge;
    }

    /** Limpia solo lo que tocó la búsqueda anterior. */
    private void reset() {
        for (int i = 0; i < touchedCount; i++) {
            distance[touched[i]] = UNREACHABLE;
            parentEdge[touched[i]] = -1;
        }
        touchedCount = 0;
        heapSize = 0;
    }

    private void push(int vertex, int dist) {
        if (heapSize == heap.length) {
            heap = Arrays.copyOf(heap, heap.length * 2);
        }
        long entry = (long) dist << 32 | (vertex & 0xFFFF_FFFFL);
        int i = heapSize++;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (heap[parent] <= entry) {
                break;
            }
            heap[i] = heap[parent];
            i = parent;
        }
        heap[i] = entry;
    }

    private long pop() {
        long top = heap[0];
        long last = heap[--heapSize];
        int i = 0;
        while (true) {
            int child = 2 * i + 1;
            if (child >= heapSize) {
                break;
            }
            if (child + 1 < heapSize && heap[child + 1] < heap[child]) {
                child++;
            }
            if (heap[child] >= last) {
                break;
            }
            heap[i] = heap[child];
            i = child;
        }
        heap[i] = last;
        return top;
    }
}
