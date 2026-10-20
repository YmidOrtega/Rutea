package me.ymid.rutea.geo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Distancias y tiempos de viaje por la red vial entre un conjunto de vértices, en arreglos planos fila
 * por fila: {@code meters[i * size + j]} va de {@code i} a {@code j}. No es simétrica: con calles de un
 * solo sentido, ir y volver cuestan distinto.
 */
public final class TravelMatrix {

    /** Mayor n con n² dentro de un arreglo de Java. */
    public static final int MAX_SIZE = 46_340;

    private final int size;
    private final int[] meters;
    private final int[] seconds;

    private TravelMatrix(int size, int[] meters, int[] seconds) {
        this.size = size;
        this.meters = meters;
        this.seconds = seconds;
    }

    /**
     * Un Dijkstra por fila y por peso. Cada hilo toma filas completas con su propio {@link ShortestPaths} y
     * escribe solo en ellas: el resultado no depende del número de hilos.
     */
    public static TravelMatrix compute(RoadGraph graph, int[] vertices, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads debe ser al menos 1: " + threads);
        }
        if (vertices.length > MAX_SIZE) {
            throw new IllegalArgumentException(vertices.length + " puntos; el máximo es " + MAX_SIZE);
        }
        for (int v : vertices) {
            if (v < 0 || v >= graph.vertexCount()) {
                throw new IllegalArgumentException("vértice fuera del grafo: " + v);
            }
        }
        int n = vertices.length;
        int[] targets = vertices.clone();
        int[] meters = new int[n * n];
        int[] seconds = new int[n * n];
        AtomicInteger nextRow = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                workers.add(pool.submit(() -> {
                    ShortestPaths byMeters = new ShortestPaths(graph, ShortestPaths.Weight.METERS);
                    ShortestPaths bySeconds = new ShortestPaths(graph, ShortestPaths.Weight.SECONDS);
                    int[] row = new int[n];
                    for (int i = nextRow.getAndIncrement(); i < n; i = nextRow.getAndIncrement()) {
                        byMeters.distances(targets[i], targets, row);
                        System.arraycopy(row, 0, meters, i * n, n);
                        bySeconds.distances(targets[i], targets, row);
                        System.arraycopy(row, 0, seconds, i * n, n);
                    }
                }));
            }
            for (Future<?> worker : workers) {
                worker.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cálculo de la matriz interrumpido", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("falló el cálculo de la matriz", e.getCause());
        } finally {
            pool.shutdownNow();
        }
        for (int i = 0; i < n * n; i++) {
            if (meters[i] == ShortestPaths.UNREACHABLE) {
                throw new IllegalStateException("no hay camino de " + vertices[i / n] + " a " + vertices[i % n]
                        + ": el grafo debería ser fuertemente conexo");
            }
        }
        return new TravelMatrix(n, meters, seconds);
    }

    public int size() {
        return size;
    }

    public int meters(int from, int to) {
        return meters[from * size + to];
    }

    public int seconds(int from, int to) {
        return seconds[from * size + to];
    }

    /** Copia fila por fila, la forma que recibe el solver. */
    public int[][] metersRows() {
        int[][] rows = new int[size][];
        for (int i = 0; i < size; i++) {
            rows[i] = Arrays.copyOfRange(meters, i * size, (i + 1) * size);
        }
        return rows;
    }
}
