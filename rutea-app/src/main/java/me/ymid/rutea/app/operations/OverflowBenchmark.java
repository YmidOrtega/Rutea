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
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

import me.ymid.rutea.app.operations.LiveOperation.Dispatch;
import me.ymid.rutea.app.operations.LiveOperation.Pickup;
import me.ymid.rutea.app.operations.LiveOperation.Policy;
import me.ymid.rutea.app.planning.AreaPlanner;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * Desbordes simulados sobre el plan de Suba: cada uno elige un contenedor y una hora del turno al azar, con semilla
 * fija, y se despacha sobre el plan original con cada {@link Policy}. Mide quién lo recoge, cuánto tarda en llegar y
 * cuánto cuesta. Para los que recoge un camión en servicio, cuenta también cuántas veces una reserva habría llegado
 * antes: es lo que se deja de lado a cambio de no sacar un camión por un contenedor.
 *
 * <p>Uso: {@code OverflowBenchmark <grafo.rgraph> <dir-salida> [--events <n>] [--seed <n>]}
 */
public final class OverflowBenchmark {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private OverflowBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("uso: OverflowBenchmark <grafo.rgraph> <dir-salida> [--events <n>] [--seed <n>]");
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

        SplittableRandom random = new SplittableRandom(seed);
        int total = BreakdownBenchmark.WARMUP + events;
        Map<Policy, Stats> stats = new EnumMap<>(Policy.class);
        for (Policy policy : Policy.values()) {
            stats.put(policy, new Stats(events));
        }
        long coldNanos = 0;
        Path csv = outDir.resolve("overflows-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        Files.createDirectories(outDir);
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("event,container,report_s,policy,pickup,truck,pickup_delay_min,extra_km,latency_ms,"
                    + "reserve_delay_min,warmup\n");
            for (int e = 0; e < total; e++) {
                int node = 1 + random.nextInt(scenario.containers().size());
                long at = random.nextLong(schedule.shiftSeconds());
                long reserveDelay = BogotaFigures.RESERVE_DISPATCH_SECONDS + scenario.travel().seconds(0, node);
                boolean warmup = e < BreakdownBenchmark.WARMUP;
                for (Policy policy : Policy.values()) {
                    Dispatch dispatch = new LiveOperation(scenario, solution, schedule, policy).overflow(node, at);
                    if (e == 0 && policy == Policy.EARLIEST) {
                        coldNanos = dispatch.latencyNanos();
                    }
                    if (!warmup) {
                        stats.get(policy).add(e - BreakdownBenchmark.WARMUP, dispatch, reserveDelay);
                    }
                    out.write(String.format(Locale.ROOT, "%d,%s,%d,%s,%s,%d,%.1f,%.3f,%.3f,%.1f,%b%n", e,
                            scenario.containers().get(node - 1).id(), at, policy, dispatch.pickup(), dispatch.truck(),
                            (dispatch.pickupSeconds() - at) / 60.0, dispatch.extraMeters() / 1000.0,
                            dispatch.latencyNanos() / 1e6, reserveDelay / 60.0, warmup));
                }
            }
        }

        System.out.printf(Locale.ROOT, "%s: %d camiones, %d desbordes medidos (más %d de calentamiento), semilla %d%n",
                scenario.instance().name(), schedule.trucks().size(), events, BreakdownBenchmark.WARMUP, seed);
        System.out.printf(Locale.ROOT, "Latencia en frío (primer desborde, JVM sin calentar): %.1f µs%n",
                coldNanos / 1e3);
        for (Policy policy : Policy.values()) {
            Stats s = stats.get(policy);
            System.out.printf(Locale.ROOT, "%n%s:%n%n| Medida | P50 | P95 | P99 | Máx. |%n|---|---:|---:|---:|---:|%n",
                    name(policy));
            BreakdownBenchmark.row("Latencia de despachar (µs)", s.latency, 1e3);
            BreakdownBenchmark.row("Reporte → recogida, todos (min)", s.delay, 60);
            for (Pickup pickup : Pickup.values()) {
                long[] some = s.delaysOf(pickup);
                if (some.length > 0) {
                    BreakdownBenchmark.row("Reporte → recogida, " + name(pickup) + " (min)", some, 60);
                }
            }
            long[] detours = s.detours();
            if (detours.length > 0) {
                BreakdownBenchmark.row("Desvío de un camión en servicio (km)", detours, 1000);
            }
            System.out.printf(Locale.ROOT, "%nRecoge: %s. km agregados en total: %.1f. Una reserva habría llegado"
                    + " antes en %d de los %d que recogió un camión en servicio.%n", s.shares(), s.extraMeters / 1000.0,
                    s.reserveSooner, detours.length);
        }
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    private static final class Stats {

        final long[] latency;
        final long[] delay;
        final long[] meters;
        final Pickup[] pickups;
        long extraMeters;
        int reserveSooner;

        Stats(int events) {
            latency = new long[events];
            delay = new long[events];
            meters = new long[events];
            pickups = new Pickup[events];
        }

        void add(int i, Dispatch dispatch, long reserveDelay) {
            latency[i] = dispatch.latencyNanos();
            delay[i] = dispatch.pickupSeconds() - dispatch.atSeconds();
            meters[i] = dispatch.extraMeters();
            pickups[i] = dispatch.pickup();
            extraMeters += dispatch.extraMeters();
            if (dispatch.pickup() == Pickup.IN_SERVICE && reserveDelay < delay[i]) {
                reserveSooner++;
            }
        }

        long[] delaysOf(Pickup pickup) {
            return IntStream.range(0, delay.length).filter(i -> pickups[i] == pickup).mapToLong(i -> delay[i])
                    .toArray();
        }

        long[] detours() {
            return IntStream.range(0, meters.length).filter(i -> pickups[i] == Pickup.IN_SERVICE)
                    .mapToLong(i -> meters[i]).toArray();
        }

        String shares() {
            StringBuilder text = new StringBuilder();
            for (Pickup pickup : Pickup.values()) {
                long count = Arrays.stream(pickups).filter(pickup::equals).count();
                text.append(text.isEmpty() ? "" : ", ").append(name(pickup)).append(' ').append(count)
                        .append(String.format(Locale.ROOT, " (%.1f %%)", 100.0 * count / pickups.length));
            }
            return text.toString();
        }
    }

    private static String name(Policy policy) {
        return switch (policy) {
            case EARLIEST -> "El camión en servicio que llega primero";
            case SHORTEST_DETOUR -> "El camión en servicio que menos se desvía";
        };
    }

    private static String name(Pickup pickup) {
        return switch (pickup) {
            case PLANNED -> "su visita del plan";
            case IN_SERVICE -> "camión en servicio";
            case RESERVE -> "reserva";
        };
    }
}
