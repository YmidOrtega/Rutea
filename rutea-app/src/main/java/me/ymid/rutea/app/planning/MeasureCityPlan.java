package me.ymid.rutea.app.planning;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.solver.Evaluator;

/**
 * Latencia de planificar el cargue lateral de 1, 5 y 20 localidades con {@link CityPlanner}, en paralelo y en serie.
 * Los dos modos tienen que dar el mismo plan: si no, algo se comparte entre hilos. Se mide dos veces en la misma JVM:
 * en frío y en caliente, cuando la primera pasada ya compiló el código.
 *
 * <p>Uso: {@code MeasureCityPlan <bogota.rgraph> <dir-extracto> <dir-salida>}
 */
public final class MeasureCityPlan {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private MeasureCityPlan() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("uso: MeasureCityPlan <bogota.rgraph> <dir-extracto> <dir-salida>");
            System.exit(2);
        }
        RoadGraph bogota = RoadGraphFile.read(Path.of(args[0])).graph();
        Map<Integer, List<OperatorContainers.Container>> real = new LinkedHashMap<>();
        for (OperatorContainers.Layer layer : OperatorContainers.LAYERS) {
            real.put(layer.area(), OperatorContainers.read(Path.of(args[1]).resolve(layer.file())));
        }
        long seed = AreaPlanner.DEFAULT_SEED;
        long iterations = AreaPlanner.ITERATIONS;
        double fill = SideLoading.MEAN_FILL;
        int cores = Runtime.getRuntime().availableProcessors();
        Map<String, List<Locality>> selections = new LinkedHashMap<>();
        selections.put("1", localities(11));
        // Una por ASE: la de más contenedores de cada una.
        selections.put("5", localities(5, 19, 8, 10, 11));
        List<Locality> all = new ArrayList<>();
        ServiceAreas.BOGOTA.forEach(area -> all.addAll(area.localities()));
        selections.put("20", all);

        Path outDir = Path.of(args[2]);
        Files.createDirectories(outDir);
        Path csv = outDir.resolve("city-plan-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        System.out.printf(Locale.ROOT, "Cargue lateral con llenado del %.0f %%, ALNS con semilla %d y %d iteraciones,"
                + " %d hilos de CPU%n", fill * 100, seed, iterations, cores);
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("localities,mode,warmup,instance,area,shift,containers,kg,trips,solution_m,trucks,"
                    + "trucks_lower_bound,scenario_ms,solve_ms,plan_ms,seed,iterations,cores\n");
            for (boolean warmup : new boolean[] {false, true}) {
                System.out.printf("%n%s%n%n", warmup ? "En caliente:" : "En frío:");
                System.out.println("| Localidades | ASE | Instancias | Contenedores | En paralelo | En serie |"
                        + " Aceleración | Instancia más lenta |");
                System.out.println("|---:|---:|---:|---:|---:|---:|---:|---|");
                for (Map.Entry<String, List<Locality>> selection : selections.entrySet()) {
                    // En paralelo primero: en frío, es la latencia de la primera petición.
                    CityPlanner.Plan parallel = CityPlanner.plan(bogota, real, selection.getValue(), fill, seed,
                            iterations, true);
                    CityPlanner.Plan series = CityPlanner.plan(bogota, real, selection.getValue(), fill, seed,
                            iterations, false);
                    sameOutcome(parallel, series);
                    write(out, selection.getKey(), "parallel", warmup, parallel, seed, iterations, cores);
                    write(out, selection.getKey(), "series", warmup, series, seed, iterations, cores);
                    print(selection.getKey(), parallel, series);
                }
            }
        }
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    private static void print(String localities, CityPlanner.Plan parallel, CityPlanner.Plan series) {
        CityPlanner.AreaPlan slowest = parallel.areas().stream()
                .max(Comparator.comparingLong(p -> p.scenarioNanos() + p.solveNanos())).orElseThrow();
        System.out.printf(Locale.ROOT, "| %s | %d | %d | %d | %.1f s | %.1f s | %.1f× | %s, %.1f s |%n", localities,
                parallel.areas().stream().map(p -> p.scenario().area()).distinct().count(), parallel.areas().size(),
                parallel.areas().stream().mapToInt(p -> p.scenario().containers().size()).sum(),
                parallel.nanos() / 1e9, series.nanos() / 1e9, (double) series.nanos() / parallel.nanos(),
                slowest.scenario().instance().name(), (slowest.scenarioNanos() + slowest.solveNanos()) / 1e9);
        for (CityPlanner.Skipped skipped : parallel.skipped()) {
            System.out.printf("  sin contenedores en la selección: %s, turno %s%n", skipped.area().name(),
                    skipped.shift().name());
        }
    }

    private static List<Locality> localities(int... numbers) {
        List<Locality> localities = new ArrayList<>();
        for (int number : numbers) {
            localities.add(ServiceAreas.locality(number).orElseThrow());
        }
        return localities;
    }

    private static void sameOutcome(CityPlanner.Plan parallel, CityPlanner.Plan series) {
        if (parallel.areas().size() != series.areas().size()) {
            throw new IllegalStateException("en paralelo salen " + parallel.areas().size() + " instancias y en serie "
                    + series.areas().size());
        }
        for (int i = 0; i < parallel.areas().size(); i++) {
            CityPlanner.AreaPlan a = parallel.areas().get(i);
            CityPlanner.AreaPlan b = series.areas().get(i);
            if (!a.scenario().instance().name().equals(b.scenario().instance().name())
                    || !a.solution().equals(b.solution())
                    || a.schedule().trucks().size() != b.schedule().trucks().size()) {
                throw new IllegalStateException(a.scenario().instance().name() + ": el plan en paralelo no es el mismo"
                        + " que en serie");
            }
        }
    }

    private static void write(BufferedWriter out, String localities, String mode, boolean warmup,
            CityPlanner.Plan plan, long seed, long iterations, int cores) throws IOException {
        for (CityPlanner.AreaPlan p : plan.areas()) {
            long kg = p.scenario().containers().stream().mapToLong(c -> c.kg()).sum();
            out.write(String.format(Locale.ROOT, "%s,%s,%b,%s,%d,%s,%d,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f,%d,%d,%d%n",
                    localities, mode, warmup, p.scenario().instance().name(), p.scenario().area().number(),
                    p.shift().name(), p.scenario().containers().size(), kg, p.solution().routeCount(),
                    Evaluator.evaluate(p.scenario().instance(), p.solution()).cost(), p.schedule().trucks().size(),
                    p.schedule().lowerBound(), p.scenarioNanos() / 1e6, p.solveNanos() / 1e6, plan.nanos() / 1e6, seed,
                    iterations, cores));
        }
    }
}
