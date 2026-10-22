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
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

import me.ymid.rutea.app.operations.ContainerSensors.Config;
import me.ymid.rutea.app.operations.LiveOperation.Dispatch;
import me.ymid.rutea.app.operations.LiveOperation.Pickup;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.planning.SubaPlanner;
import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * Qué tanto se desbordan los contenedores de Suba según lo que no se sabe: la densidad de los residuos sueltos, el
 * tamaño de los contenedores y si es un día cualquiera o un lunes. Para cada combinación cuenta cuántos se llenan
 * antes de su visita del plan y cuánto tiempo pasan llenos. Con la mezcla de tamaños, además, deja que los sensores
 * despachen cada desborde apenas ocurre (ADR 0006, el camión que llega primero) y mide cuánto se acorta ese tiempo.
 *
 * <p>Uso: {@code SensorBenchmark <grafo.rgraph> <dir-salida> [--seed <n>]}
 */
public final class SensorBenchmark {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final double[] DENSITIES = {100, 150, 200};
    private static final int[] DAYS = {1, 2};

    private SensorBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("uso: SensorBenchmark <grafo.rgraph> <dir-salida> [--seed <n>]");
            System.exit(2);
        }
        Path outDir = Path.of(args[1]);
        long seed = 1;
        for (int i = 2; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                default -> throw new IllegalArgumentException("argumento no reconocido: " + args[i]);
            }
        }
        SubaScenario scenario = SubaScenario.build(RoadGraphFile.read(Path.of(args[0])).graph(), seed);
        Solution solution = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(seed, Duration.ofMinutes(10), SubaPlanner.ITERATIONS));
        ShiftScheduler.Schedule schedule = SubaPlanner.fewestTrucks(scenario, solution, SubaFigures.UNLOAD_SECONDS_LOW);
        int n = scenario.containers().size();
        int[] planned = new int[n];
        for (ShiftScheduler.Truck truck : schedule.trucks()) {
            for (ShiftScheduler.Visit visit : ShiftScheduler.timeline(scenario, solution, truck,
                    SubaFigures.SERVICE_SECONDS_PER_CONTAINER, schedule.unloadSeconds(), 0)) {
                planned[visit.node() - 1] = (int) visit.arriveSeconds();
            }
        }

        List<List<Integer>> mixes = new ArrayList<>();
        for (int liters : SubaFigures.CONTAINER_LITERS) {
            mixes.add(List.of(liters));
        }
        mixes.add(SubaFigures.CONTAINER_LITERS);
        Path csv = outDir.resolve("sensors-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        Files.createDirectories(outDir);
        StringBuilder planTable = new StringBuilder();
        StringBuilder dispatchTable = new StringBuilder();
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("container,liters,density_kg_m3,days,dispatch,full_s,pickup_s,pickup,truck,extra_km\n");
            for (int days : DAYS) {
                for (double density : DENSITIES) {
                    for (List<Integer> sizes : mixes) {
                        Config config = new Config(density, days, seed);
                        ContainerSensors sensors = ContainerSensors.of(scenario.containers(), planned, sizes, config);
                        planTable.append(planRow(scenario, sensors, planned, sizes, config, out));
                        if (sizes.size() > 1) {
                            dispatchTable.append(dispatchRow(scenario, solution, schedule, sensors, sizes, config,
                                    out));
                        }
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "%s: %d contenedores, semilla %d. Mezcla: un tamaño al azar entre %s L.%n",
                scenario.instance().name(), n, seed, SubaFigures.CONTAINER_LITERS);
        System.out.printf(Locale.ROOT, "%nLlenos antes de su visita del plan, sin despachar nada:%n%n"
                + "| Día | kg/m³ | Contenedores | Se desbordan | Llenos P50 | P95 |%n|---|---:|---|---:|---:|---:|%n%s",
                planTable);
        System.out.printf(Locale.ROOT, "%nCon los sensores despachando cada desborde apenas ocurre (mezcla de"
                + " tamaños):%n%n| Día | kg/m³ | Desbordes | Antes del turno | Su visita | Camión en servicio | Reserva"
                + " | Llenos P50 | P95 | km agregados |%n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|%n%s",
                dispatchTable);
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    /** Sin despachar: cada contenedor espera su visita del plan. */
    private static String planRow(SubaScenario scenario, ContainerSensors sensors, int[] planned, List<Integer> sizes,
            Config config, BufferedWriter out) throws IOException {
        List<Long> full = new ArrayList<>();
        for (int i = 0; i < sensors.size(); i++) {
            double[] visit = {planned[i]};
            OptionalDouble crossing = sensors.fullBetween(i, visit, Double.NEGATIVE_INFINITY, planned[i]);
            if (crossing.isPresent()) {
                full.add(Math.round(planned[i] - crossing.getAsDouble()));
                out.write(String.format(Locale.ROOT, "%s,%d,%.0f,%d,false,%.0f,%d,PLANNED,,0%n",
                        scenario.containers().get(i).id(), sensors.liters(i), config.densityKgPerM3(),
                        config.daysSinceLastCollection(), crossing.getAsDouble(), planned[i]));
            }
        }
        return String.format(Locale.ROOT, "| %s | %.0f | %s | %.1f %% | %s | %s |%n", day(config),
                config.densityKgPerM3(), sizes(sizes), 100.0 * full.size() / sensors.size(), hours(full, 0.50),
                hours(full, 0.95));
    }

    /**
     * Los sensores reportan cada desborde a la hora en que ocurre y se despacha con la regla en vivo. Después de
     * cada despacho cambian las horas de llegada, así que el siguiente desborde se busca otra vez sobre el plan nuevo.
     */
    private static String dispatchRow(SubaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule,
            ContainerSensors sensors, List<Integer> sizes, Config config, BufferedWriter out) throws IOException {
        LiveOperation operation = new LiveOperation(scenario, solution, schedule,
                SubaFigures.SERVICE_SECONDS_PER_CONTAINER);
        int n = sensors.size();
        List<List<Double>> reserve = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            reserve.add(new ArrayList<>());
        }
        Map<Pickup, Integer> pickups = new EnumMap<>(Pickup.class);
        List<Long> full = new ArrayList<>();
        int beforeShift = 0;
        long meters = 0;
        double now = Double.NEGATIVE_INFINITY;
        while (true) {
            double[][] visits = visits(operation, reserve, n);
            int next = -1;
            double nextAt = Double.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                OptionalDouble crossing = sensors.fullBetween(i, visits[i], now, operation.shiftSeconds());
                if (crossing.isPresent() && crossing.getAsDouble() < nextAt) {
                    nextAt = crossing.getAsDouble();
                    next = i;
                }
            }
            if (next < 0) {
                break;
            }
            // Un contenedor ya lleno al empezar el turno se reporta a las 18:00.
            long at = Math.max(0, (long) Math.ceil(nextAt));
            Dispatch dispatch = operation.overflow(next + 1, at);
            if (dispatch.pickup() == Pickup.RESERVE) {
                reserve.get(next).add((double) dispatch.pickupSeconds());
            }
            pickups.merge(dispatch.pickup(), 1, Integer::sum);
            beforeShift += nextAt < 0 ? 1 : 0;
            full.add(dispatch.pickupSeconds() - Math.round(nextAt));
            meters += dispatch.extraMeters();
            out.write(String.format(Locale.ROOT, "%s,%d,%.0f,%d,true,%.0f,%d,%s,%d,%.3f%n",
                    scenario.containers().get(next).id(), sensors.liters(next), config.densityKgPerM3(),
                    config.daysSinceLastCollection(), nextAt, dispatch.pickupSeconds(), dispatch.pickup(),
                    dispatch.truck(), dispatch.extraMeters() / 1000.0));
            now = nextAt;
        }
        return String.format(Locale.ROOT, "| %s | %.0f | %d | %d | %d | %d | %d | %s | %s | %.1f |%n", day(config),
                config.densityKgPerM3(), full.size(), beforeShift, pickups.getOrDefault(Pickup.PLANNED, 0),
                pickups.getOrDefault(Pickup.IN_SERVICE, 0), pickups.getOrDefault(Pickup.RESERVE, 0),
                hours(full, 0.50), hours(full, 0.95), meters / 1000.0);
    }

    /** Las llegadas a cada contenedor, en orden: las de los camiones (del plan y extra) y las de las reservas. */
    private static double[][] visits(LiveOperation operation, List<List<Double>> reserve, int n) {
        List<List<Double>> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            all.add(new ArrayList<>(reserve.get(i)));
        }
        for (int t = 0; t < operation.truckCount(); t++) {
            for (LiveOperation.Visit visit : operation.visits(t)) {
                all.get(visit.node() - 1).add((double) visit.arriveSeconds());
            }
        }
        double[][] visits = new double[n][];
        for (int i = 0; i < n; i++) {
            visits[i] = all.get(i).stream().mapToDouble(Double::doubleValue).sorted().toArray();
        }
        return visits;
    }

    private static String day(Config config) {
        return config.daysSinceLastCollection() == 1 ? "Martes a sábado" : "Lunes";
    }

    private static String sizes(List<Integer> sizes) {
        return sizes.size() == 1 ? sizes.getFirst() + " L" : "mezcla";
    }

    /** "h:mm" del percentil, o "—" si no hay. */
    private static String hours(List<Long> seconds, double p) {
        if (seconds.isEmpty()) {
            return "—";
        }
        long[] sorted = seconds.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        long value = BreakdownBenchmark.pick(sorted, p);
        long minutes = Math.round(value / 60.0);
        return String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60);
    }
}
