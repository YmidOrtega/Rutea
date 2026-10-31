package me.ymid.rutea.app.planning;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.solver.Evaluation;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * Suba con los 806 contenedores simulados de F3 a F6 (supuesto de 2022) frente a los 320 del Plan de Contenerización
 * de 2026 (ADR 0013), sobre el mismo grafo y con la misma semilla de ubicación. La de 2026 se mide con cada llenado de
 * {@link SideLoading#FILLS}. ALNS con límite de iteraciones: el resultado depende solo de la semilla.
 *
 * <p>Uso: {@code CompareSuba2026 <suba.rgraph> <dir-salida>}
 */
public final class CompareSuba2026 {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private static final long PLACEMENT_SEED = 1;

    /** La de la comparación de F4 ({@code CompareBaseline}): la fila de los 806 reproduce sus cifras publicadas. */
    private static final long ALNS_SEED = 1;

    private CompareSuba2026() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("uso: CompareSuba2026 <suba.rgraph> <dir-salida>");
            System.exit(2);
        }
        RoadGraph suba = RoadGraphFile.read(Path.of(args[0])).graph();
        ServiceAreas.ServiceArea area = ServiceAreas.ASE_5;
        long seed = ALNS_SEED;
        long iterations = AreaPlanner.ITERATIONS;
        // Toneladas del ASE 5: las de la ciudad × su 15 % de suelo urbano, el mismo supuesto de F3 (PENDIENTE_VERIFICAR).
        double areaKgPerDay = BogotaFigures.BOGOTA_ORDINARY_TONNES_PER_DAY * 1000.0 * area.urbanAreaShare();
        List<Integer> liters = area.verified().containerLiters();

        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);
        Path csv = outDir.resolve("suba-2026-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        System.out.printf(Locale.ROOT, "Suba (ASE %d): semilla de ubicación %d, ALNS con semilla %d y %d iteraciones;"
                + " toneladas del ASE supuestas: %.0f t/día%n%n", area.number(), PLACEMENT_SEED, seed, iterations,
                areaKgPerDay / 1000);
        System.out.println("| Instancia | Contenedores | t por recolección | t/día | Parte de las toneladas | Viajes |"
                + " km del solver | Camiones, 40 min | Camiones, 90 min |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|---:|");
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("instance,containers,fill,kg_per_collection,collections_per_week,tonnes_per_day,share,seed,"
                    + "iterations,trips,solution_m,max_trips_per_truck,trucks_unload_40,lower_bound_unload_40,"
                    + "schedule_m_unload_40,trucks_unload_90,lower_bound_unload_90,schedule_m_unload_90\n");
            write(out, AreaScenario.build(suba, area, PLACEMENT_SEED), Double.NaN, areaKgPerDay, seed, iterations);
            for (double fill : SideLoading.FILLS) {
                write(out, AreaScenario.buildPlanned(suba, area, SideLoading.ASE_5_NIGHT, SideLoading.ASE_5_CONTAINERS,
                        liters, fill, PLACEMENT_SEED), fill, areaKgPerDay, seed, iterations);
            }
        }
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    private static void write(BufferedWriter out, AreaScenario scenario, double fill, double areaKgPerDay, long seed,
            long iterations) throws IOException {
        Solution solution = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(seed, Duration.ofMinutes(10), iterations));
        Evaluation evaluation = Evaluator.evaluate(scenario.instance(), solution);
        if (!evaluation.feasible()) {
            throw new IllegalStateException(scenario.instance().name() + " infactible: " + evaluation.violations());
        }
        long kg = scenario.containers().stream().mapToLong(c -> c.kg()).sum();
        int perWeek = scenario.operation().collectionsPerWeek();
        double kgPerDay = kg * perWeek / 7.0;
        int maxTrips = scenario.operation().maxTripsPerTruck();
        ShiftScheduler.Schedule fast = schedule(scenario, solution, BogotaFigures.UNLOAD_SECONDS_LOW);
        ShiftScheduler.Schedule slow = schedule(scenario, solution, BogotaFigures.UNLOAD_SECONDS_HIGH);
        out.write(String.format(Locale.ROOT, "%s,%d,%s,%d,%d,%.1f,%.4f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                scenario.instance().name(), scenario.containers().size(),
                Double.isNaN(fill) ? "" : String.format(Locale.ROOT, "%.2f", fill), kg, perWeek, kgPerDay / 1000,
                kgPerDay / areaKgPerDay, seed, iterations, solution.routeCount(), evaluation.cost(), maxTrips,
                fast.trucks().size(), fast.lowerBound(), fast.meters(), slow.trucks().size(), slow.lowerBound(),
                slow.meters()));
        System.out.printf(Locale.ROOT, "| %s | %d | %.1f | %.1f | %.1f %% | %d | %.1f | %d | %d |%n",
                scenario.instance().name(), scenario.containers().size(), kg / 1000.0, kgPerDay / 1000,
                100 * kgPerDay / areaKgPerDay, solution.routeCount(), evaluation.cost() / 1000.0, fast.trucks().size(),
                slow.trucks().size());
    }

    private static ShiftScheduler.Schedule schedule(AreaScenario scenario, Solution solution, int unloadSeconds) {
        return ShiftScheduler.schedule(scenario, solution, unloadSeconds,
                scenario.operation().shiftSeconds(), scenario.operation().maxTripsPerTruck());
    }
}
