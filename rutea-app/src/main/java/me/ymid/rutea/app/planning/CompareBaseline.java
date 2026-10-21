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

import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.domain.DieselEmissions;
import me.ymid.rutea.geo.RoadGraphFile;
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
        PlanReport rutea = PlanReport.of(SubaPlanner.plan(scenario, new AlnsSolver(),
                new SolverSettings(seed, Duration.ofMinutes(10), iterations)));
        double seconds = (System.nanoTime() - start) / 1e9;

        List<PlanReport> all = new ArrayList<>(baselines);
        all.add(rutea);
        Path csv = outDir.resolve("compare-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        writeCsv(csv, all, scenario.instance().name(), seed, iterations);

        System.out.printf(Locale.ROOT, "%s: %d contenedores; consumo supuesto %.2f L/km; %.2f kg CO2/L (IPCC 2006)%n%n",
                scenario.instance().name(), scenario.containers().size(), SubaFigures.LITERS_PER_KM,
                DieselEmissions.kgCo2PerLiter());
        System.out.println("| Método | Turnos | km | Horas | t CO₂ |");
        System.out.println("|---|---:|---:|---:|---:|");
        for (PlanReport report : all) {
            PlanReport.Line t = report.total();
            System.out.printf(Locale.ROOT, "| `%s`%s | %d | %.1f | %.1f | %.2f |%n", report.method(),
                    report == best ? " (mejor barrido)" : "", report.trips(), t.km(), t.hours(), t.kgCo2() / 1000);
        }
        System.out.printf(Locale.ROOT, "%nRutea (%s, semilla %d, %d iteraciones, %.1f s) frente al mejor barrido: "
                + "%.1f %% menos km, %.1f %% menos horas, %.1f %% menos CO₂ (%.2f t por turno de recolección)%n",
                rutea.method(), seed, iterations, seconds, 100 * rutea.savingsVs(best, PlanReport.Line::km),
                100 * rutea.savingsVs(best, PlanReport.Line::hours), 100 * rutea.savingsVs(best, PlanReport.Line::kgCo2),
                (best.total().kgCo2() - rutea.total().kgCo2()) / 1000);
        System.out.println("CSV: " + csv.toAbsolutePath());
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
