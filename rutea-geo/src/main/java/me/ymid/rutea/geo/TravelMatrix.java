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
 *
 * <p>Los segundos son los de recorrer el camino más corto en metros, no los del camino más rápido: es el
 * camino que el solver optimiza y el que se dibuja, así distancia y tiempo hablan del mismo recorrido.
 */
public final class TravelMatrix {

    /** Mayor n con n² dentro de un arreglo de Java. */
    public static final int MAX_SIZE = 46_340;

    public static final int UNREACHABLE = ShortestPaths.UNREACHABLE;

    /** @param affectedPairs celdas cuyo camino más corto pasaba por un arco cerrado: las únicas que pueden cambiar */
    public record Repair(TravelMatrix matrix, int closedEdges, int affectedPairs, int changedPairs, int rows,
            int columns, long nanos) {
    }

    private final RoadGraph graph;
    private final int[] vertices;
    private final int size;
    private final int[] meters;
    private final int[] seconds;

    private TravelMatrix(RoadGraph graph, int[] vertices, int[] meters, int[] seconds) {
        this.graph = graph;
        this.vertices = vertices;
        this.size = vertices.length;
        this.meters = meters;
        this.seconds = seconds;
    }

    /**
     * Un Dijkstra por fila, por metros, que acumula los segundos del mismo camino. Cada hilo toma filas
     * completas con su propio {@link ShortestPaths} y escribe solo en ellas: el resultado no depende del
     * número de hilos.
     */
    public static TravelMatrix compute(RoadGraph graph, int[] vertices, int threads) {
        TravelMatrix matrix = computeAllowingUnreachable(graph, vertices, threads);
        for (int i = 0; i < matrix.meters.length; i++) {
            if (matrix.meters[i] == UNREACHABLE) {
                throw new IllegalStateException("no hay camino de " + vertices[i / vertices.length] + " a "
                        + vertices[i % vertices.length] + ": el grafo debería ser fuertemente conexo");
            }
        }
        return matrix;
    }

    /** Como {@link #compute}, sobre un grafo con vías cerradas: lo que da {@link #closing}, calculado de cero. */
    public static TravelMatrix computeAllowingUnreachable(RoadGraph graph, int[] vertices, int threads) {
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
        parallel(graph, n, threads, n, (worker, i) -> {
            worker.forward().distances(targets[i], targets, worker.row, worker.rowSeconds);
            System.arraycopy(worker.row, 0, meters, i * n, n);
            System.arraycopy(worker.rowSeconds, 0, seconds, i * n, n);
        });
        for (int i = 0; i < n * n; i++) {
            if (meters[i] == UNREACHABLE) {
                seconds[i] = UNREACHABLE;
            }
        }
        return new TravelMatrix(graph, targets, meters, seconds);
    }

    /**
     * Un arco cerrado {@code u → v} solo cambia la celda {@code (i, j)} si {@code d(i, u) + arco + d(v, j) = d(i, j)}
     * en metros y en segundos. Las celdas tocadas se cubren con filas (Dijkstra desde el punto) y columnas (Dijkstra
     * hacia el punto, en el grafo invertido): cerrar la calle de un contenedor cambia casi toda su columna, que sale
     * con una sola búsqueda. Como el desempate de {@link ShortestPaths} da un único valor por celda, el resultado es
     * idéntico a {@link #compute} sobre el grafo cerrado, salvo que admite {@link #UNREACHABLE}.
     */
    public Repair closing(int[] shapes, int threads) {
        long start = System.nanoTime();
        RoadGraph after = graph.closing(shapes);
        IntList closed = new IntList(2 * shapes.length);
        for (int shape : shapes) {
            for (int e : graph.edgesOn(shape)) {
                closed.add(e);
            }
        }
        int n = size;
        int arcs = closed.size();
        int[][] inMeters = new int[arcs][n];
        int[][] inSeconds = new int[arcs][n];
        int[][] outMeters = new int[arcs][n];
        int[][] outSeconds = new int[arcs][n];
        parallel(graph, n, threads, 2 * arcs, (worker, task) -> {
            int k = task / 2;
            int e = closed.get(k);
            if (task % 2 == 0) {
                worker.backward().distances(graph.source(e), vertices, inMeters[k], inSeconds[k]);
            } else {
                worker.forward().distances(graph.target(e), vertices, outMeters[k], outSeconds[k]);
            }
        });

        boolean[] affected = new boolean[n * n];
        int[] perRow = new int[n];
        int[] perColumn = new int[n];
        int affectedPairs = 0;
        for (int k = 0; k < arcs; k++) {
            int e = closed.get(k);
            long arcMeters = graph.meters(e);
            long arcSeconds = graph.seconds(e);
            int[] out = outMeters[k];
            int[] outTime = outSeconds[k];
            for (int i = 0; i < n; i++) {
                if (inMeters[k][i] == UNREACHABLE) {
                    continue;
                }
                long viaMeters = inMeters[k][i] + arcMeters;
                long viaSeconds = inSeconds[k][i] + arcSeconds;
                for (int j = 0; j < n; j++) {
                    int cell = i * n + j;
                    if (!affected[cell] && out[j] != UNREACHABLE && viaMeters + out[j] == meters[cell]
                            && viaSeconds + outTime[j] == seconds[cell]) {
                        affected[cell] = true;
                        perRow[i]++;
                        perColumn[j]++;
                        affectedPairs++;
                    }
                }
            }
        }

        // Cobertura voraz por filas y columnas.
        IntList rows = new IntList(8);
        IntList columns = new IntList(8);
        boolean[] pending = affected.clone();
        while (true) {
            int bestRow = argmax(perRow);
            int bestColumn = argmax(perColumn);
            if (perRow[bestRow] == 0 && perColumn[bestColumn] == 0) {
                break;
            }
            if (perRow[bestRow] >= perColumn[bestColumn]) {
                rows.add(bestRow);
                for (int j = 0; j < n; j++) {
                    if (pending[bestRow * n + j]) {
                        pending[bestRow * n + j] = false;
                        perColumn[j]--;
                    }
                }
                perRow[bestRow] = 0;
            } else {
                columns.add(bestColumn);
                for (int i = 0; i < n; i++) {
                    if (pending[i * n + bestColumn]) {
                        pending[i * n + bestColumn] = false;
                        perRow[i]--;
                    }
                }
                perColumn[bestColumn] = 0;
            }
        }

        int[] newMeters = meters.clone();
        int[] newSeconds = seconds.clone();
        // Dos fases, para que una celda en una fila y una columna elegidas no se escriba desde dos hilos a la vez.
        parallel(after, n, threads, rows.size(), (worker, task) -> {
            int i = rows.get(task);
            worker.forward().distances(vertices[i], vertices, worker.row, worker.rowSeconds);
            System.arraycopy(worker.row, 0, newMeters, i * n, n);
            System.arraycopy(worker.rowSeconds, 0, newSeconds, i * n, n);
        });
        parallel(after, n, threads, columns.size(), (worker, task) -> {
            int j = columns.get(task);
            worker.backward().distances(vertices[j], vertices, worker.row, worker.rowSeconds);
            for (int i = 0; i < n; i++) {
                newMeters[i * n + j] = worker.row[i];
                newSeconds[i * n + j] = worker.rowSeconds[i];
            }
        });
        int changed = 0;
        for (int cell = 0; cell < n * n; cell++) {
            if (newMeters[cell] == UNREACHABLE) {
                newSeconds[cell] = UNREACHABLE;
            }
            if (newMeters[cell] != meters[cell] || newSeconds[cell] != seconds[cell]) {
                changed++;
            }
        }
        return new Repair(new TravelMatrix(after, vertices, newMeters, newSeconds), arcs, affectedPairs, changed,
                rows.size(), columns.size(), System.nanoTime() - start);
    }

    public RoadGraph graph() {
        return graph;
    }

    public int vertex(int i) {
        return vertices[i];
    }

    public boolean reachable(int from, int to) {
        return meters[from * size + to] != UNREACHABLE;
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

    private static int argmax(int[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    private interface Task {
        void run(Worker worker, int index);
    }

    /** Una reparación suele ser un puñado de Dijkstra: con una tarea no vale la pena el pool. */
    private static void parallel(RoadGraph graph, int points, int threads, int count, Task task) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads debe ser al menos 1: " + threads);
        }
        int workers = Math.min(threads, count);
        if (workers <= 1) {
            Worker worker = new Worker(graph, points);
            for (int i = 0; i < count; i++) {
                task.run(worker, i);
            }
            return;
        }
        AtomicInteger next = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < workers; t++) {
                futures.add(pool.submit(() -> {
                    Worker worker = new Worker(graph, points);
                    for (int i = next.getAndIncrement(); i < count; i = next.getAndIncrement()) {
                        task.run(worker, i);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cálculo de la matriz interrumpido", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("falló el cálculo de la matriz", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private static final class Worker {

        private final RoadGraph graph;
        private ShortestPaths forward;
        private ShortestPaths backward;
        private final int[] row;
        private final int[] rowSeconds;

        Worker(RoadGraph graph, int points) {
            this.graph = graph;
            row = new int[points];
            rowSeconds = new int[points];
        }

        ShortestPaths forward() {
            if (forward == null) {
                forward = new ShortestPaths(graph, ShortestPaths.Weight.METERS);
            }
            return forward;
        }

        ShortestPaths backward() {
            if (backward == null) {
                backward = new ShortestPaths(graph.reversed(), ShortestPaths.Weight.METERS);
            }
            return backward;
        }
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
