package me.ymid.rutea.app.planning;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Collectors;

import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.domain.DieselEmissions;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * Criterio de cierre de F4: compara el plan de Rutea con el barrido ingenuo por cuadrantes en Suba, en km,
 * horas y CO₂. El barrido se prueba con varios tamaños de cuadrante y se compara contra el mejor, para no
 * ganarle a una línea base mal elegida. Todo es determinista: ALNS va con límite de iteraciones.
 *
 * <p>Uso: {@code CompareBaseline <grafo.rgraph> <dir-salida> [--seed <n>] [--iterations <n>]}
 */
public final class CompareBaseline {

    static final int[] CELL_METERS = {500, 1_000, 2_000, 4_000};
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private CompareBaseline() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("uso: CompareBaseline <grafo.rgraph> <dir-salida> [--seed <n>] [--iterations <n>]");
            System.exit(2);
        }
        Path graphFile = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        long seed = 1;
        long iterations = SubaPlanner.ITERATIONS;
        for (int i = 2; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                case "--iterations" -> iterations = Long.parseLong(args[i + 1]);
                default -> throw new IllegalArgumentException("argumento no reconocido: " + args[i]);
            }
        }

        SubaScenario scenario = SubaScenario.build(RoadGraphFile.read(graphFile).graph(), seed);
        List<PlanReport> baselines = new ArrayList<>();
        for (int cell : CELL_METERS) {
            baselines.add(PlanReport.of(SubaPlanner.plan(scenario, QuadrantSweep.solve(scenario, cell),
                    QuadrantSweep.name(cell), seed, 0)));
        }
        PlanReport best = baselines.stream().min(Comparator.comparingDouble(r -> r.total().km())).orElseThrow();
        long start = System.nanoTime();
        Solution alns = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(seed, Duration.ofMinutes(10), iterations));
        double seconds = (System.nanoTime() - start) / 1e9;
        PlanReport rutea = PlanReport.of(SubaPlanner.plan(scenario, alns, "alns", seed, iterations));

        List<PlanReport> all = new ArrayList<>(baselines);
        all.add(rutea);
        Path csv = outDir.resolve("compare-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        writeCsv(csv, all, scenario.instance().name(), seed, iterations);

        System.out.printf(Locale.ROOT, "%s: %d contenedores; consumo supuesto %.2f L/km; %.2f kg CO2/L (IPCC 2006)%n%n",
                scenario.instance().name(), scenario.containers().size(), SubaFigures.LITERS_PER_KM,
                DieselEmissions.kgCo2PerLiter());
        System.out.println("| Método | Turnos | km | Horas | t CO₂ | Turnos de más de 8 h |");
        System.out.println("|---|---:|---:|---:|---:|---:|");
        for (PlanReport report : all) {
            PlanReport.Line t = report.total();
            long overtime = report.lines().stream().filter(l -> l.hours() * 3600 > SubaFigures.SHIFT_SECONDS).count();
            System.out.printf(Locale.ROOT, "| `%s`%s | %d | %.1f | %.1f | %.2f | %d |%n", report.method(),
                    report == best ? " (mejor barrido)" : "", report.trips(), t.km(), t.hours(), t.kgCo2() / 1000,
                    overtime);
        }
        System.out.printf(Locale.ROOT, "%nRutea (%s, semilla %d, %d iteraciones, %.1f s) frente al mejor barrido: "
                + "%.1f %% menos km, %.1f %% menos horas, %.1f %% menos CO₂ (%.2f t por turno de recolección)%n",
                rutea.method(), seed, iterations, seconds, 100 * rutea.savingsVs(best, PlanReport.Line::km),
                100 * rutea.savingsVs(best, PlanReport.Line::hours), 100 * rutea.savingsVs(best, PlanReport.Line::kgCo2),
                (best.total().kgCo2() - rutea.total().kgCo2()) / 1000);
        System.out.println("CSV: " + csv.toAbsolutePath());

        printFleet(scenario, alns, seed, iterations, outDir);
    }

    /**
     * Los mismos viajes de ALNS, con un camión por viaje o encadenados en el menor número de camiones, en los
     * dos extremos del tiempo de descarga publicado: cuántos camiones hacen falta y qué cuesta en km.
     */
    private static void printFleet(SubaScenario scenario, Solution alns, long seed, long iterations, Path outDir)
            throws IOException {
        record Option(String name, ShiftScheduler.Schedule schedule) {
        }
        List<Option> options = List.of(
                new Option("un viaje por camión", SubaPlanner.oneTripEach(scenario, alns, SubaFigures.UNLOAD_SECONDS_HIGH)),
                new Option("menos camiones, descarga de 40 min",
                        SubaPlanner.fewestTrucks(scenario, alns, SubaFigures.UNLOAD_SECONDS_LOW)),
                new Option("menos camiones, descarga de 90 min",
                        SubaPlanner.fewestTrucks(scenario, alns, SubaFigures.UNLOAD_SECONDS_HIGH)));
        System.out.printf(Locale.ROOT, "%nLos %d viajes de alns repartidos en camiones (turno de %d h desde las %s):%n%n",
                alns.routeCount(), SubaFigures.SHIFT_SECONDS / 3600, SubaFigures.SHIFT_START);
        System.out.println("| Reparto | Camiones | Viajes por camión | km | Horas de camión | t CO₂ | Último regreso | Turnos de más de 8 h |");
        System.out.println("|---|---:|---|---:|---:|---:|---:|---:|");
        Path csv = outDir.resolve("fleet-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        Files.createDirectories(outDir);
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("instance,seed,iterations,option,unload_s,trucks,km,truck_hours,kg_co2,last_return_s,overtime_trucks\n");
            for (Option option : options) {
                PlanReport report = PlanReport.of(SubaPlanner.plan(scenario, alns, option.schedule(), "alns", seed,
                        iterations));
                long last = option.schedule().trucks().stream().mapToLong(ShiftScheduler.Truck::seconds).max()
                        .orElseThrow();
                String tripsPerTruck = option.schedule().trucks().stream().map(t -> t.trips().length)
                        .collect(Collectors.groupingBy(n -> n, TreeMap::new,
                                Collectors.counting()))
                        .entrySet().stream().map(e -> e.getValue() + "×" + e.getKey())
                        .collect(Collectors.joining(", "));
                System.out.printf(Locale.ROOT, "| %s | %d | %s | %.1f | %.1f | %.2f | %s | %d |%n", option.name(),
                        report.trips(), tripsPerTruck, report.total().km(), report.total().hours(),
                        report.total().kgCo2() / 1000, clock(last), option.schedule().overtimeTrucks());
                out.write(String.format(Locale.ROOT, "%s,%d,%d,%s,%d,%d,%.3f,%.3f,%.2f,%d,%d%n", scenario.instance().name(),
                        seed, iterations, option.name(), option.schedule().unloadSeconds(), report.trips(),
                        report.total().km(), report.total().hours(), report.total().kgCo2(), last,
                        option.schedule().overtimeTrucks()));
            }
        }
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    private static String clock(long secondsAfterStart) {
        String[] hhmm = SubaFigures.SHIFT_START.split(":");
        long total = Long.parseLong(hhmm[0]) * 3600 + Long.parseLong(hhmm[1]) * 60 + secondsAfterStart;
        return String.format(Locale.ROOT, "%02d:%02d", (total / 3600) % 24, (total % 3600) / 60);
    }

    /** Nunca pisa una corrida anterior: si el archivo ya existe, falla. */
    static void writeCsv(Path csv, List<PlanReport> reports, String instance, long seed, long iterations)
            throws IOException {
        Files.createDirectories(csv.toAbsolutePath().getParent());
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("instance,method,seed,iterations,trips,km,hours,tonnes,liters,kg_co2,liters_per_km\n");
            for (PlanReport r : reports) {
                PlanReport.Line t = r.total();
                boolean baseline = r.method().startsWith("barrido");
                out.write(String.format(Locale.ROOT, "%s,%s,%d,%s,%d,%.3f,%.3f,%.3f,%.2f,%.2f,%.3f%n", instance,
                        r.method(), seed, baseline ? "" : Long.toString(iterations), r.trips(), t.km(), t.hours(),
                        t.tonnes(), t.liters(), t.kgCo2(), SubaFigures.LITERS_PER_KM));
            }
        }
    }
}
