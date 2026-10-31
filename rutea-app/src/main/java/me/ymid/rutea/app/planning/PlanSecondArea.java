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
import me.ymid.rutea.app.scenario.OperatorContainers;
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
 * El cargue lateral del ASE 2 con sus contenedores reales: cada turno del operador por separado, con cada llenado de
 * {@link SideLoading#FILLS} y las dos descargas del rango publicado. Dice cuántos compactadores hacen falta frente a
 * los que declara Tunjuelo. ALNS con límite de iteraciones: el resultado depende solo de la semilla.
 *
 * <p>Uso: {@code PlanSecondArea <bogota.rgraph> <dir-extracto> <dir-salida>}
 */
public final class PlanSecondArea {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private PlanSecondArea() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("uso: PlanSecondArea <bogota.rgraph> <dir-extracto> <dir-salida>");
            System.exit(2);
        }
        RoadGraph bogota = RoadGraphFile.read(Path.of(args[0])).graph();
        ServiceAreas.ServiceArea area = ServiceAreas.ASE_2;
        SideLoading.Area sideLoading = SideLoading.of(area);
        OperatorContainers.Layer layer = OperatorContainers.LAYERS.stream().filter(l -> l.area() == area.number())
                .findFirst().orElseThrow();
        List<OperatorContainers.Container> real = OperatorContainers.read(Path.of(args[1]).resolve(layer.file()));
        long seed = AreaPlanner.DEFAULT_SEED;
        long iterations = AreaPlanner.ITERATIONS;
        int declared = sideLoading.sideLoaders().orElseThrow();
        int threads = Runtime.getRuntime().availableProcessors();

        Path outDir = Path.of(args[2]);
        Files.createDirectories(outDir);
        Path csv = outDir.resolve("second-area-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        System.out.printf(Locale.ROOT, "ASE %d (%s): %d contenedores reales, compactadores de %,d kg, semilla %d,"
                + " %d iteraciones de ALNS, %d hilos; Tunjuelo declara %d de cargue lateral%n%n", area.number(),
                area.operator(), real.size(), SideLoading.SIDE_LOADER_KG, seed, iterations, threads, declared);
        System.out.println("| Turno | Contenedores | Llenado | t | Viajes | Descarga | Camiones | Cota inferior |"
                + " km | Horas de camión | Escenario |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("instance,shift,containers,fill,kg,capacity_kg,seed,iterations,trips,solution_m,unload_s,"
                    + "shift_s,trucks,trucks_lower_bound,overtime_trucks,schedule_m,truck_hours,declared_side_loaders,"
                    + "scenario_ms,solve_ms,threads\n");
            for (SideLoading.Shift shift : sideLoading.shifts()) {
                for (double fill : SideLoading.FILLS) {
                    long start = System.nanoTime();
                    // Incluye ubicar cada contenedor en su trazo más cercano, que recorre todos los trazos del grafo.
                    AreaScenario scenario = AreaScenario.buildReal(bogota, area, shift, real, fill);
                    double scenarioMs = (System.nanoTime() - start) / 1e6;
                    start = System.nanoTime();
                    Solution solution = new AlnsSolver().solve(scenario.instance(),
                            new SolverSettings(seed, Duration.ofMinutes(10), iterations));
                    double solveMs = (System.nanoTime() - start) / 1e6;
                    Evaluation evaluation = Evaluator.evaluate(scenario.instance(), solution);
                    if (!evaluation.feasible()) {
                        throw new IllegalStateException(scenario.instance().name() + " infactible: "
                                + evaluation.violations());
                    }
                    long kg = scenario.containers().stream().mapToLong(c -> c.kg()).sum();
                    for (int unload : new int[] {BogotaFigures.UNLOAD_SECONDS_LOW, BogotaFigures.UNLOAD_SECONDS_HIGH}) {
                        ShiftScheduler.Schedule schedule = ShiftScheduler.schedule(scenario, solution, unload, shift.seconds(), scenario.operation().maxTripsPerTruck());
                        long truckSeconds = schedule.trucks().stream().mapToLong(ShiftScheduler.Truck::seconds).sum();
                        out.write(String.format(Locale.ROOT, "%s,%s,%d,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%d,"
                                + "%.1f,%.1f,%d%n", scenario.instance().name(), shift.name(),
                                scenario.containers().size(), fill, kg, scenario.instance().capacity(), seed, iterations,
                                solution.routeCount(), evaluation.cost(), unload, shift.seconds(),
                                schedule.trucks().size(), schedule.lowerBound(), schedule.overtimeTrucks(),
                                schedule.meters(), truckSeconds / 3600.0, declared, scenarioMs, solveMs, threads));
                        System.out.printf(Locale.ROOT, "| %s | %d | %.0f %% | %.1f | %d | %d min | %d%s | %d | %.1f |"
                                + " %.1f | %.0f ms |%n", shift.name(), scenario.containers().size(), fill * 100,
                                kg / 1000.0, solution.routeCount(), unload / 60, schedule.trucks().size(),
                                schedule.overtimeTrucks() > 0 ? " (" + schedule.overtimeTrucks() + " con horas extra)"
                                        : "", schedule.lowerBound(), schedule.meters() / 1000.0, truckSeconds / 3600.0,
                                scenarioMs);
                    }
                }
            }
        }
        System.out.println("CSV: " + csv.toAbsolutePath());
    }
}
