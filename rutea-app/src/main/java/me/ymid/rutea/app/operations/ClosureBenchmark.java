package me.ymid.rutea.app.operations;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

import me.ymid.rutea.app.operations.LiveOperation.Closure;
import me.ymid.rutea.app.operations.LiveOperation.ClosurePolicy;
import me.ymid.rutea.app.operations.LiveOperation.TruckChange;
import me.ymid.rutea.app.planning.AreaPlanner;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * Cierres de vía simulados sobre el plan de Suba. Cada uno toma una hora al azar, un compactador que sigue en
 * servicio y una calle del camino que le falta: un cierre donde importa, porque uno al azar casi nunca toca una ruta.
 * Se aplica con cada {@link ClosurePolicy} y, además, se recalcula la matriz completa sobre el grafo cerrado para
 * medir cuánto se ahorra y comprobar que la reparada es idéntica.
 *
 * <p>Uso: {@code ClosureBenchmark <grafo.rgraph> <dir-salida> [--events <n>] [--seed <n>]}
 */
public final class ClosureBenchmark {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private ClosureBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("uso: ClosureBenchmark <grafo.rgraph> <dir-salida> [--events <n>] [--seed <n>]");
            System.exit(2);
        }
        Path outDir = Path.of(args[1]);
        int events = 500;
        long seed = 1;
        for (int i = 2; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--events" -> events = Integer.parseInt(args[i + 1]);
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                default -> throw new IllegalArgumentException("argumento no reconocido: " + args[i]);
            }
        }

        AreaScenario scenario = AreaScenario.build(RoadGraphFile.read(Path.of(args[0])).graph(), ServiceAreas.ASE_5, seed);
        Solution solution = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(seed, Duration.ofMinutes(10), AreaPlanner.ITERATIONS));
        ShiftScheduler.Schedule schedule = AreaPlanner.fewestTrucks(scenario, solution, BogotaFigures.UNLOAD_SECONDS_LOW);
        LivePlan original = LivePlan.of(scenario, solution, schedule, BogotaFigures.SERVICE_SECONDS_PER_CONTAINER);
        RoadGraph graph = scenario.graph();
        int[] vertices = new int[scenario.travel().size()];
        for (int i = 0; i < vertices.length; i++) {
            vertices[i] = scenario.travel().vertex(i);
        }
        int threads = Runtime.getRuntime().availableProcessors();

        SplittableRandom random = new SplittableRandom(seed);
        int total = BreakdownBenchmark.WARMUP + events;
        Map<ClosurePolicy, Stats> stats = new EnumMap<>(ClosurePolicy.class);
        for (ClosurePolicy policy : ClosurePolicy.values()) {
            stats.put(policy, new Stats(events));
        }
        long[] fullNanos = new long[events];
        long[] recomputed = new long[events];
        int identical = 0;
        int isolating = 0;
        int refused = 0;
        long coldNanos = 0;
        Path csv = outDir.resolve("closures-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        Files.createDirectories(outDir);
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("event,closure_s,truck,shape,road_class,policy,latency_ms,repair_ms,full_matrix_ms,rows,columns,"
                    + "changed_pairs,trucks_changed,extra_km,worst_delay_min,late_trucks,rerouted,isolated,dropped,"
                    + "identical,warmup\n");
            int e = 0;
            while (e < total) {
                long at = random.nextLong(schedule.shiftSeconds());
                int truck = random.nextInt(original.truckCount());
                List<Integer> ahead = streetsAhead(original, truck, at);
                if (ahead.isEmpty()) {
                    continue;
                }
                int shape = ahead.get(random.nextInt(ahead.size()));
                TravelMatrix.Repair probe = scenario.travel().closing(new int[] {shape}, threads);
                int gate = scenario.gate();
                if (!probe.matrix().reachable(0, gate) || !probe.matrix().reachable(gate, 0)) {
                    refused++;
                    continue;
                }
                boolean warmup = e < BreakdownBenchmark.WARMUP;
                int k = e - BreakdownBenchmark.WARMUP;

                long fullStart = System.nanoTime();
                TravelMatrix scratch = TravelMatrix.computeAllowingUnreachable(graph.closing(shape), vertices,
                        threads);
                long full = System.nanoTime() - fullStart;
                boolean same = sameCells(probe.matrix(), scratch);

                for (ClosurePolicy policy : ClosurePolicy.values()) {
                    LiveOperation operation = new LiveOperation(scenario, solution, schedule,
                            BogotaFigures.SERVICE_SECONDS_PER_CONTAINER);
                    operation.closurePolicy(policy);
                    Closure closure = operation.close(shape, at);
                    if (e == 0 && policy == ClosurePolicy.REROUTE) {
                        coldNanos = closure.latencyNanos();
                    }
                    if (!warmup) {
                        stats.get(policy).add(k, closure, schedule.shiftSeconds());
                    }
                    TravelMatrix.Repair repair = closure.repair();
                    out.write(String.format(Locale.ROOT,
                            "%d,%d,%d,%d,%s,%s,%.3f,%.3f,%.3f,%d,%d,%d,%d,%.3f,%.1f,%d,%b,%d,%d,%b,%b%n", e, at, truck,
                            shape, graph.roadClass(graph.edgesOn(shape)[0]), policy, closure.latencyNanos() / 1e6,
                            repair.nanos() / 1e6, full / 1e6, repair.rows(), repair.columns(), repair.changedPairs(),
                            closure.changes().size(), extraMeters(closure) / 1000.0, worstDelay(closure) / 60.0,
                            late(closure), closure.changes().stream().anyMatch(TruckChange::rerouted),
                            closure.isolated().size(), closure.dropped().size(), same, warmup));
                }
                if (!warmup) {
                    fullNanos[k] = full;
                    recomputed[k] = probe.rows() + probe.columns();
                    identical += same ? 1 : 0;
                    isolating += stats.get(ClosurePolicy.REROUTE).isolated[k] > 0 ? 1 : 0;
                }
                e++;
            }
        }

        System.out.printf(Locale.ROOT, "%s: %d camiones, %d cierres medidos (más %d de calentamiento), semilla %d,"
                + " %d hilos%n", scenario.instance().name(), schedule.trucks().size(), events,
                BreakdownBenchmark.WARMUP, seed, threads);
        System.out.printf(Locale.ROOT, "Latencia en frío (primer cierre, JVM sin calentar): %.1f ms%n",
                coldNanos / 1e6);
        System.out.printf(Locale.ROOT, "Matriz reparada idéntica a la calculada de cero: %d de %d. Cierres que aíslan"
                + " contenedores: %d. Descartados por aislar la base o la portería: %d.%n", identical, events,
                isolating, refused);
        System.out.printf(Locale.ROOT, "%n| Medida | P50 | P95 | P99 | Máx. |%n|---|---:|---:|---:|---:|%n");
        BreakdownBenchmark.row("Reparar la matriz (ms)", stats.get(ClosurePolicy.REROUTE).repair, 1e6);
        BreakdownBenchmark.row("Matriz completa de cero (ms)", fullNanos, 1e6);
        BreakdownBenchmark.row("Filas + columnas recalculadas, de " + vertices.length, recomputed, 1);
        for (ClosurePolicy policy : ClosurePolicy.values()) {
            Stats s = stats.get(policy);
            System.out.printf(Locale.ROOT, "%n%s:%n%n| Medida | P50 | P95 | P99 | Máx. |%n|---|---:|---:|---:|---:|%n",
                    name(policy));
            BreakdownBenchmark.row("Latencia: reparar y replanificar (ms)", s.latency, 1e6);
            BreakdownBenchmark.row("km agregados", s.meters, 1000);
            BreakdownBenchmark.row("Retraso del camión más afectado (min)", s.delay, 60);
            System.out.printf(Locale.ROOT, "%nkm agregados en total: %.1f. Cierres con algún camión fuera de turno:"
                    + " %d. Con un camión desviado a mitad de camino: %d.%n", s.totalMeters / 1000.0, s.lateEvents,
                    s.reroutedEvents);
        }
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    /** Trazos que el camión recorre después de {@code at}, en los tramos que todavía no termina. */
    private static List<Integer> streetsAhead(LivePlan plan, int truck, long at) {
        List<Integer> shapes = new ArrayList<>();
        for (LivePlan.Leg leg : plan.legs(truck)) {
            if (leg.arriveSeconds() <= at) {
                continue;
            }
            long clock = leg.departSeconds();
            for (AreaPlanner.Arc arc : plan.arcs(leg)) {
                clock += arc.seconds();
                int shape = arc.graph().shape(arc.edge());
                if (clock > at && arc.graph().shapeInLocality(shape < 0 ? ~shape : shape)) {
                    shapes.add(shape < 0 ? ~shape : shape);
                }
            }
        }
        return shapes;
    }

    private static boolean sameCells(TravelMatrix a, TravelMatrix b) {
        for (int i = 0; i < a.size(); i++) {
            for (int j = 0; j < a.size(); j++) {
                if (a.meters(i, j) != b.meters(i, j) || a.seconds(i, j) != b.seconds(i, j)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long extraMeters(Closure closure) {
        return closure.changes().stream().mapToLong(TruckChange::extraMeters).sum();
    }

    private static long worstDelay(Closure closure) {
        return closure.changes().stream().mapToLong(TruckChange::extraSeconds).max().orElse(0);
    }

    private static long late(Closure closure) {
        return closure.changes().stream().filter(TruckChange::late).count();
    }

    private static final class Stats {

        final long[] latency;
        final long[] repair;
        final long[] meters;
        final long[] delay;
        final int[] isolated;
        long totalMeters;
        int lateEvents;
        int reroutedEvents;

        Stats(int events) {
            latency = new long[events];
            repair = new long[events];
            meters = new long[events];
            delay = new long[events];
            isolated = new int[events];
        }

        void add(int i, Closure closure, long shiftSeconds) {
            latency[i] = closure.latencyNanos();
            repair[i] = closure.repair().nanos();
            meters[i] = extraMeters(closure);
            delay[i] = worstDelay(closure);
            isolated[i] = closure.isolated().size();
            totalMeters += meters[i];
            lateEvents += late(closure) > 0 ? 1 : 0;
            reroutedEvents += closure.changes().stream().anyMatch(TruckChange::rerouted) ? 1 : 0;
        }
    }

    private static String name(ClosurePolicy policy) {
        return switch (policy) {
            case REROUTE -> "Solo desviar: mismo orden de paradas";
            case RESEQUENCE -> "Desviar y reordenar las paradas que faltan";
        };
    }
}
