package me.ymid.rutea.app.planning;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.Localities;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.ServiceAreas.ServiceArea;
import me.ymid.rutea.app.scenario.SimulatedContainers;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Evaluation;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * Suba sobre el grafo de Suba y sobre el de Bogotá (ADR 0012): los mismos contenedores, en los mismos nodos de OSM,
 * con la misma semilla e iteraciones de ALNS. En el grafo de Bogotá una ruta puede tomar calles locales de las
 * localidades vecinas, así que ninguna distancia debería crecer. También mide la matriz de la ASE más grande, el ASE 2,
 * cerca de los ~1.700 contenedores donde F3 previó el primer ajuste de escala del solver.
 *
 * <p>Uso: {@code CompareGraphs <suba.rgraph> <bogota.rgraph> <dir-salida> [--seed <n>] [--iterations <n>]}
 */
public final class CompareGraphs {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private record Timed(TravelMatrix matrix, double coldMs, double warmMs) {
    }

    private CompareGraphs() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("uso: CompareGraphs <suba.rgraph> <bogota.rgraph> <dir-salida> [--seed <n>]"
                    + " [--iterations <n>]");
            System.exit(2);
        }
        long seed = 1;
        long iterations = AreaPlanner.ITERATIONS;
        for (int i = 3; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                case "--iterations" -> iterations = Long.parseLong(args[i + 1]);
                default -> throw new IllegalArgumentException("argumento no reconocido: " + args[i]);
            }
        }
        RoadGraph suba = RoadGraphFile.read(Path.of(args[0])).graph();
        RoadGraph bogota = RoadGraphFile.read(Path.of(args[1])).graph();
        Path outDir = Path.of(args[2]);
        int threads = Runtime.getRuntime().availableProcessors();

        AreaScenario scenario = AreaScenario.build(suba, ServiceAreas.ASE_5, seed);
        int count = scenario.containers().size();
        int[] onSuba = Arrays.copyOf(scenario.points(), count + 2);
        onSuba[count + 1] = scenario.gateVertex();
        int[] onBogota = new int[onSuba.length];
        for (int i = 0; i < onSuba.length; i++) {
            long node = suba.osmNodeId(onSuba[i]);
            onBogota[i] = bogota.vertexOf(node).orElseThrow(
                    () -> new IllegalStateException("el grafo de Bogotá no tiene el nodo " + node));
        }
        Timed subaMatrix = timed(suba, onSuba, threads);
        Timed bogotaMatrix = timed(bogota, onBogota, threads);

        int n = onSuba.length;
        long cells = 0;
        long shorter = 0;
        long longer = 0;
        long savedMeters = 0;
        int maxSaved = 0;
        int maxExtra = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                int delta = subaMatrix.matrix().meters(i, j) - bogotaMatrix.matrix().meters(i, j);
                cells++;
                if (delta > 0) {
                    shorter++;
                    savedMeters += delta;
                    maxSaved = Math.max(maxSaved, delta);
                } else if (delta < 0) {
                    longer++;
                    maxExtra = Math.max(maxExtra, -delta);
                }
            }
        }

        SolverSettings settings = new SolverSettings(seed, Duration.ofMinutes(10), iterations);
        Instance subaInstance = instance(scenario.instance(), subaMatrix.matrix());
        Instance bogotaInstance = instance(scenario.instance(), bogotaMatrix.matrix());
        Solution subaSolution = new AlnsSolver().solve(subaInstance, settings);
        Evaluation onSubaGraph = Evaluator.evaluate(subaInstance, subaSolution);
        Solution bogotaSolution = new AlnsSolver().solve(bogotaInstance, settings);
        Evaluation onBogotaGraph = Evaluator.evaluate(bogotaInstance, bogotaSolution);
        if (!onSubaGraph.feasible() || !onBogotaGraph.feasible()) {
            throw new IllegalStateException("solución infactible: " + onSubaGraph.violations() + " "
                    + onBogotaGraph.violations());
        }

        // El ASE 2 aún no tiene base verificada: la matriz sale de la base de Suba, que solo cambia una fila.
        ServiceArea largest = ServiceAreas.ASE_2;
        int scaleCount = largest.nonRecyclableContainers();
        List<Container> spread = SimulatedContainers.place(bogota,
                AreaScenario.streets(bogota, largest, Localities.bogota()), WasteStream.NON_RECYCLABLE, scaleCount, 160,
                seed);
        int[] scalePoints = new int[scaleCount + 2];
        scalePoints[0] = onBogota[0];
        for (int i = 0; i < scaleCount; i++) {
            scalePoints[i + 1] = spread.get(i).vertex();
        }
        scalePoints[scaleCount + 1] = onBogota[n - 1];
        Timed scale = timed(bogota, scalePoints, threads);

        Files.createDirectories(outDir);
        Path csv = outDir.resolve("graphs-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("graph,vertices,edges,points,threads,matrix_cold_ms,matrix_warm_ms,seed,iterations,cost_m,trips\n");
            out.write(row("suba", suba, n, threads, subaMatrix, seed, iterations, onSubaGraph.cost(),
                    subaSolution.routeCount()));
            out.write(row("bogota", bogota, n, threads, bogotaMatrix, seed, iterations, onBogotaGraph.cost(),
                    bogotaSolution.routeCount()));
            out.write(row("bogota-ase" + largest.number(), bogota, scalePoints.length, threads, scale, seed, 0, -1, 0));
        }

        System.out.printf(Locale.ROOT, "%s: %d contenedores, semilla %d, %d iteraciones de ALNS, %d hilos%n%n",
                scenario.instance().name(), count, seed, iterations, threads);
        System.out.println("| Grafo | Vértices | Arcos | Matriz en frío | En caliente | Costo ALNS |");
        System.out.println("|---|---:|---:|---:|---:|---:|");
        System.out.printf(Locale.ROOT, "| Suba | %,d | %,d | %.0f ms | %.0f ms | %.1f km |%n", suba.vertexCount(),
                suba.edgeCount(), subaMatrix.coldMs(), subaMatrix.warmMs(), onSubaGraph.cost() / 1000.0);
        System.out.printf(Locale.ROOT, "| Bogotá | %,d | %,d | %.0f ms | %.0f ms | %.1f km |%n", bogota.vertexCount(),
                bogota.edgeCount(), bogotaMatrix.coldMs(), bogotaMatrix.warmMs(), onBogotaGraph.cost() / 1000.0);
        System.out.printf(Locale.ROOT, "%nCeldas de la matriz (%,d × %,d sin la diagonal): %,d más cortas en Bogotá"
                + " (%.1f %%), %,d más largas (a lo sumo %d m); ahorro medio %.1f m por celda, máximo %,d m%n", n, n,
                shorter, 100.0 * shorter / cells, longer, maxExtra, (double) savedMeters / cells, maxSaved);
        System.out.printf(Locale.ROOT, "Costo ALNS: %.2f %% menos en el grafo de Bogotá%n",
                100.0 * (onSubaGraph.cost() - onBogotaGraph.cost()) / onSubaGraph.cost());
        System.out.printf(Locale.ROOT, "Matriz del ASE %d (%,d contenedores, %,d lugares): %.0f ms en frío, %.0f ms"
                + " en caliente%n", largest.number(), scaleCount, scalePoints.length, scale.coldMs(), scale.warmMs());
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    /** La misma instancia con otra matriz: [base, contenedores..., portería], con la portería en la columna 0. */
    private static Instance instance(Instance template, TravelMatrix matrix) {
        int size = template.size();
        int gate = size;
        int[][] distances = new int[size][size];
        int[] demands = new int[size];
        for (int i = 0; i < size; i++) {
            demands[i] = template.demand(i);
            for (int j = 0; j < size; j++) {
                distances[i][j] = i == j ? 0 : matrix.meters(i, j == 0 ? gate : j);
            }
        }
        return new Instance(template.name(), template.capacity(), demands, DistanceMatrix.of(distances));
    }

    /** La primera corrida incluye calentar la JVM; la segunda, no. */
    private static Timed timed(RoadGraph graph, int[] points, int threads) {
        long start = System.nanoTime();
        TravelMatrix matrix = TravelMatrix.compute(graph, points, threads);
        double cold = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        TravelMatrix.compute(graph, points, threads);
        return new Timed(matrix, cold, (System.nanoTime() - start) / 1e6);
    }

    private static String row(String name, RoadGraph graph, int points, int threads, Timed timed, long seed,
            long iterations, long cost, int trips) {
        return String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%.1f,%.1f,%d,%s,%s,%s%n", name, graph.vertexCount(),
                graph.edgeCount(), points, threads, timed.coldMs(), timed.warmMs(), seed,
                iterations > 0 ? Long.toString(iterations) : "", cost >= 0 ? Long.toString(cost) : "",
                trips > 0 ? Integer.toString(trips) : "");
    }
}
