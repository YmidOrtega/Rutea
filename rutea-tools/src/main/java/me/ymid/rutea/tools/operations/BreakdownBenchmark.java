package me.ymid.rutea.tools.operations;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;

import me.ymid.rutea.app.operations.BreakdownReplanner;
import me.ymid.rutea.app.planning.AreaPlanner;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.DoorToDoor;
import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.geo.RoadGraph;
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
 * <p>Con {@code --locality}, {@code --slot} y {@code --containers}, sobre la operación en vivo de una localidad en una
 * franja del puerta a puerta (F8), con el grafo de Bogotá.
 *
 * <p>Uso: {@code BreakdownBenchmark <grafo.rgraph> <dir-salida> [--events <n>] [--seed <n>] [--locality <n> --slot
 * <franja> --containers <data/ase>]}
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
            System.err.println("uso: BreakdownBenchmark <grafo.rgraph> <dir-salida> [--events <n>] [--seed <n>]"
                    + " [--locality <n> --slot <franja> --containers <data/ase>]");
            System.exit(2);
        }
        Path outDir = Path.of(args[1]);
        int events = 500;
        long seed = 1;
        int locality = 0;
        String slot = null;
        Path containers = null;
        for (int i = 2; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--events" -> events = Integer.parseInt(args[i + 1]);
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                case "--locality" -> locality = Integer.parseInt(args[i + 1]);
                case "--slot" -> slot = args[i + 1];
                case "--containers" -> containers = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("argumento no reconocido: " + args[i]);
            }
        }

        RoadGraph graph = RoadGraphFile.read(Path.of(args[0])).graph();
        AreaScenario scenario = locality == 0 ? AreaScenario.build(graph, ServiceAreas.ASE_5, seed)
                : doorToDoor(graph, locality, Objects.requireNonNull(slot, "--slot"),
                        Objects.requireNonNull(containers, "--containers"));
        Solution solution = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(seed, Duration.ofMinutes(10), AreaPlanner.ITERATIONS));
        ShiftScheduler.Schedule schedule = AreaPlanner.fewestTrucks(scenario, solution, BogotaFigures.UNLOAD_SECONDS_LOW);

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
        long[] reassignLatency = new long[events];
        long[] reassignRecovery = new long[events];
        long[] absorbed = new long[events];
        long reserveOnlyTrucks = 0;
        long reassignTrucks = 0;
        long reserveOnlyExtra = 0;
        long reassignExtra = 0;
        int reassignRecovered = 0;
        long avoidTrucks = 0;
        long avoidExtra = 0;
        int avoidRecovered = 0;
        int avoided = 0;
        long[] avoidRecovery = new long[events];
        Path csv = outDir.resolve("breakdowns-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        Files.createDirectories(outDir);
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("event,truck,breakdown_s,orphans,reserve_trucks,latency_ms,scratch_latency_ms,reserve_km,"
                    + "scratch_reserve_km,first_pickup_delay_min,r05_ok,reassigned,reassign_reserve_trucks,"
                    + "reassign_latency_ms,reassign_extra_km,reassign_first_pickup_delay_min,warmup\n");
            for (int e = 0; e < total; e++) {
                int truck = random.nextInt(schedule.trucks().size());
                long at = random.nextLong(schedule.trucks().get(truck).seconds());
                BreakdownReplanner.Replan replan = BreakdownReplanner.afterBreakdown(scenario, solution, schedule,
                        truck, at, REPLAN_ITERATIONS, seed + e);
                long[] scratch = fromScratch(scenario, replan, schedule, seed + e);
                BreakdownReplanner.Replan reassign = BreakdownReplanner.afterBreakdown(scenario, solution, schedule,
                        truck, at, REPLAN_ITERATIONS, seed + e, BreakdownReplanner.Strategy.REASSIGN_FIRST);
                BreakdownReplanner.Replan avoid = BreakdownReplanner.afterBreakdown(scenario, solution, schedule,
                        truck, at, REPLAN_ITERATIONS, seed + e, BreakdownReplanner.Strategy.REASSIGN_IF_NO_RESERVE);
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
                    reassignLatency[i] = reassign.latencyNanos();
                    reassignRecovery[i] = reassign.firstPickupSeconds() - at;
                    absorbed[i] = reassign.reassigned().size();
                    reserveOnlyTrucks += replan.reserve().trucks().size();
                    reassignTrucks += reassign.reserve().trucks().size();
                    reserveOnlyExtra += replan.extraMeters();
                    reassignExtra += reassign.extraMeters();
                    reassignRecovered += reassign.meetsRecoveryRule() ? 1 : 0;
                    avoidTrucks += avoid.reserve().trucks().size();
                    avoidExtra += avoid.extraMeters();
                    avoidRecovered += avoid.meetsRecoveryRule() ? 1 : 0;
                    avoided += avoid.orphans().length > 0 && avoid.reserve().trucks().isEmpty() ? 1 : 0;
                    avoidRecovery[i] = avoid.firstPickupSeconds() - at;
                }
                out.write(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.1f,%b,%d,%d,%.3f,%.3f,%.1f,%b%n",
                        e, truck, at, replan.orphans().length, replan.reserve().trucks().size(),
                        replan.latencyNanos() / 1e6, scratch[0] / 1e6, replan.reserveMeters() / 1000.0,
                        scratch[1] / 1000.0, (replan.firstPickupSeconds() - at) / 60.0, replan.meetsRecoveryRule(),
                        reassign.reassigned().size(), reassign.reserve().trucks().size(), reassign.latencyNanos() / 1e6,
                        reassign.extraMeters() / 1000.0, (reassign.firstPickupSeconds() - at) / 60.0, warmup));
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
        System.out.printf(Locale.ROOT, "%nReasignar primero a camiones en servicio y lo que no quepa a la reserva:%n%n");
        System.out.println("| Medida | P50 | P95 | P99 | Máx. |");
        System.out.println("|---|---:|---:|---:|---:|");
        row("Latencia, reasignar + reserva (ms)", reassignLatency, 1e6);
        row("Huérfanos absorbidos por camiones en servicio", absorbed, 1);
        row("Avería → primer huérfano recogido (min)", reassignRecovery, 60);
        System.out.printf(Locale.ROOT, "%n| Estrategia | Camiones de reserva (total) | km agregados (total) | R05 |%n");
        System.out.println("|---|---:|---:|---:|");
        System.out.printf(Locale.ROOT, "| Solo reserva | %d | %.1f | %d/%d |%n", reserveOnlyTrucks,
                reserveOnlyExtra / 1000.0, recovered, events);
        System.out.printf(Locale.ROOT, "| Reasignar + reserva | %d | %.1f | %d/%d |%n", reassignTrucks,
                reassignExtra / 1000.0, reassignRecovered, events);
        System.out.printf(Locale.ROOT, "| Reasignar solo si evita la reserva | %d | %.1f | %d/%d |%n", avoidTrucks,
                avoidExtra / 1000.0, avoidRecovered, events);
        System.out.printf(Locale.ROOT, "%nReasignar evitó la reserva en %d de %d averías.%n", avoided, events);
        row("Avería → primer huérfano, reasignar solo si evita la reserva (min)", avoidRecovery, 60);
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    /**
     * {latencia en ns, metros de la reserva} resolviendo los mismos huérfanos con ALNS desde cero. Los viajes pasan
     * por el mismo reparto en camiones, así los metros se comparan igual que en el arranque en caliente.
     */
    private static AreaScenario doorToDoor(RoadGraph graph, int number, String code, Path containers)
            throws IOException {
        ServiceAreas.Locality locality = ServiceAreas.locality(number)
                .orElseThrow(() -> new IllegalArgumentException("no hay localidad " + number));
        DoorToDoor.Slot slot = DoorToDoor.Slot.ALL.stream().filter(candidate -> candidate.code().equals(code))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no hay franja " + code));
        Map<Integer, List<OperatorContainers.Container>> real = new LinkedHashMap<>();
        for (OperatorContainers.Layer layer : OperatorContainers.LAYERS) {
            real.put(layer.area(), OperatorContainers.read(containers.resolve(layer.file())));
        }
        return DoorToDoor.live(graph, locality, slot, real, AreaPlanner.DEFAULT_SEED).scenario();
    }

    private static long[] fromScratch(AreaScenario scenario, BreakdownReplanner.Replan replan,
            ShiftScheduler.Schedule schedule, long seed) {
        if (replan.orphans().length == 0) {
            return new long[] {0, 0};
        }
        long start = System.nanoTime();
        Instance part = BreakdownReplanner.subInstance(scenario.instance(), replan.orphans());
        Solution solved = new AlnsSolver().solve(part, new SolverSettings(seed, Duration.ofMinutes(10), REPLAN_ITERATIONS));
        Solution trips = BreakdownReplanner.toOriginalNodes(solved, replan.orphans());
        ShiftScheduler.Schedule reserve = ShiftScheduler.schedule(scenario, trips, schedule.unloadSeconds(),
                scenario.operation().shiftSeconds(), scenario.operation().maxTripsPerTruck());
        return new long[] {System.nanoTime() - start, reserve.meters()};
    }

    static void row(String name, long[] values, double unit) {
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
