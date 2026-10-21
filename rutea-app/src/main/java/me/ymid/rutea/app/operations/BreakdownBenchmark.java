package me.ymid.rutea.app.operations;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;

import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.planning.SubaPlanner;
import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * Criterio de cierre de F5: averías simuladas sobre el plan de Suba, con la latencia de replanificar medida. Cada
 * avería elige un camión y una hora de su turno al azar, con semilla fija. Las primeras {@link #WARMUP} calientan la
 * JVM y no cuentan; la primera de todas se reporta aparte como latencia en frío.
 *
 * <p>Uso: {@code BreakdownBenchmark <grafo.rgraph> <dir-salida> [--events <n>] [--seed <n>]}
 */
public final class BreakdownBenchmark {

    static final int WARMUP = 20;

    /** Iteraciones de ALNS al replanificar: los huérfanos son pocos y el plan nuevo tiene que salir rápido. */
    static final long REPLAN_ITERATIONS = 300;

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private BreakdownBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("uso: BreakdownBenchmark <grafo.rgraph> <dir-salida> [--events <n>] [--seed <n>]");
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

        SubaScenario scenario = SubaScenario.build(RoadGraphFile.read(Path.of(args[0])).graph(), seed);
        Solution solution = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(seed, Duration.ofMinutes(10), SubaPlanner.ITERATIONS));
        ShiftScheduler.Schedule schedule = SubaPlanner.fewestTrucks(scenario, solution, SubaFigures.UNLOAD_SECONDS_LOW);

        SplittableRandom random = new SplittableRandom(seed);
        int total = WARMUP + events;
        long[] latency = new long[events];
        long[] scratchLatency = new long[events];
        long[] recovery = new long[events];
        int[] orphans = new int[events];
        int recovered = 0;
        long warmMeters = 0;
        long scratchMeters = 0;
        long coldNanos = 0;
        Path csv = outDir.resolve("breakdowns-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        Files.createDirectories(outDir);
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("event,truck,breakdown_s,orphans,reserve_trucks,latency_ms,scratch_latency_ms,reserve_km,"
                    + "scratch_reserve_km,first_pickup_delay_min,r05_ok,warmup\n");
            for (int e = 0; e < total; e++) {
                int truck = random.nextInt(schedule.trucks().size());
                long at = random.nextLong(schedule.trucks().get(truck).seconds());
                BreakdownReplanner.Replan replan = BreakdownReplanner.afterBreakdown(scenario, solution, schedule,
                        truck, at, REPLAN_ITERATIONS, seed + e);
                long[] scratch = fromScratch(scenario, replan, schedule, seed + e);
                boolean warmup = e < WARMUP;
                if (e == 0) {
                    coldNanos = replan.latencyNanos();
                }
                if (!warmup) {
                    int i = e - WARMUP;
                    latency[i] = replan.latencyNanos();
                    scratchLatency[i] = scratch[0];
                    recovery[i] = replan.firstPickupSeconds() - at;
                    orphans[i] = replan.orphans().length;
                    recovered += replan.meetsRecoveryRule() ? 1 : 0;
                    warmMeters += replan.reserveMeters();
                    scratchMeters += scratch[1];
                }
                out.write(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.1f,%b,%b%n", e, truck, at,
                        replan.orphans().length, replan.reserve().trucks().size(), replan.latencyNanos() / 1e6,
                        scratch[0] / 1e6, replan.reserveMeters() / 1000.0, scratch[1] / 1000.0,
                        (replan.firstPickupSeconds() - at) / 60.0, replan.meetsRecoveryRule(), warmup));
            }
        }

        System.out.printf(Locale.ROOT, "%s: %d camiones, %d averías medidas (más %d de calentamiento), semilla %d,"
                + " %d iteraciones de ALNS por replanificación%n%n", scenario.instance().name(), schedule.trucks().size(),
                events, WARMUP, seed, REPLAN_ITERATIONS);
        System.out.println("| Medida | P50 | P95 | P99 | Máx. |");
        System.out.println("|---|---:|---:|---:|---:|");
        row("Latencia, arranque en caliente (ms)", latency, 1e6);
        row("Latencia, ALNS desde cero (ms)", scratchLatency, 1e6);
        row("Contenedores huérfanos", Arrays.stream(orphans).asLongStream().toArray(), 1);
        row("Avería → reserva en el primer huérfano (min)", recovery, 60);
        System.out.printf(Locale.ROOT, "%nLatencia en frío (primera avería, JVM sin calentar): %.1f ms%n", coldNanos / 1e6);
        System.out.printf(Locale.ROOT, "R05 (servicio restablecido en ≤ 3 h): %d de %d averías (%.1f %%)%n", recovered,
                events, 100.0 * recovered / events);
        System.out.printf(Locale.ROOT, "km de la reserva: %.1f con arranque en caliente, %.1f desde cero (%+.2f %%)%n",
                warmMeters / 1000.0, scratchMeters / 1000.0, 100.0 * (warmMeters - scratchMeters) / scratchMeters);
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    /**
     * {latencia en ns, metros de la reserva} resolviendo los mismos huérfanos con ALNS desde cero. Los viajes pasan
     * por el mismo reparto en camiones, así los metros se comparan igual que en el arranque en caliente.
     */
    private static long[] fromScratch(SubaScenario scenario, BreakdownReplanner.Replan replan,
            ShiftScheduler.Schedule schedule, long seed) {
        if (replan.orphans().length == 0) {
            return new long[] {0, 0};
        }
        long start = System.nanoTime();
        Instance part = BreakdownReplanner.subInstance(scenario.instance(), replan.orphans());
        Solution solved = new AlnsSolver().solve(part, new SolverSettings(seed, Duration.ofMinutes(10), REPLAN_ITERATIONS));
        Solution trips = BreakdownReplanner.toOriginalNodes(solved, replan.orphans());
        ShiftScheduler.Schedule reserve = ShiftScheduler.schedule(scenario, trips,
                SubaFigures.SERVICE_SECONDS_PER_CONTAINER, schedule.unloadSeconds(), SubaFigures.SHIFT_SECONDS, 3);
        return new long[] {System.nanoTime() - start, reserve.meters()};
    }

    private static void row(String name, long[] values, double unit) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        System.out.printf(Locale.ROOT, "| %s | %.1f | %.1f | %.1f | %.1f |%n", name, pick(sorted, 0.50) / unit,
                pick(sorted, 0.95) / unit, pick(sorted, 0.99) / unit, sorted[sorted.length - 1] / unit);
    }

    /** Percentil por rango más cercano: el valor en la posición ⌈p·n⌉, sin interpolar. */
    static long pick(long[] sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }
}
