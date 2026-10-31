package me.ymid.rutea.app.planning;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.DoorToDoor;
import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.solver.Evaluation;
import me.ymid.rutea.solver.Evaluator;

/**
 * Suba en vivo, con contenedores y puerta a puerta en sus cuatro franjas, frente al plan de F3 a F6: los 806
 * contenedores simulados de 2022 sobre el grafo de Suba ({@code SubaBase-n807-s1}, congelado). Solo no aprovechables,
 * los únicos que van casa por casa. Cada plan se arma como lo sirve la app: la base como {@link DemoPlanExporter} y
 * cada franja como la operación en vivo, con la misma semilla, las mismas iteraciones y 40 min de descarga.
 *
 * <p>Uso: {@code CompareSubaLive <suba.rgraph> <suba-sitios.csv> <bogota.rgraph> <bogota-sitios.csv> <dir-salida>}
 */
public final class CompareSubaLive {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private static final int SUBA = 11;

    private CompareSubaLive() {
    }

    record Row(String name, String graph, int containers, int chunks, long kg, double kgPerDay, int trucks,
            int trips, PlanReport.Line total, int nightNoise, int noCompaction, double buildSeconds) {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 5) {
            System.err.println("uso: CompareSubaLive <suba.rgraph> <suba-sitios.csv> <bogota.rgraph>"
                    + " <bogota-sitios.csv> <dir-salida>");
            System.exit(2);
        }
        long seed = AreaPlanner.DEFAULT_SEED;
        ServiceAreas.Locality suba = ServiceAreas.locality(SUBA).orElseThrow();
        double subaKgPerDay = BogotaFigures.kgPerDay(suba);

        RoadGraph subaGraph = RoadGraphFile.read(Path.of(args[0])).graph();
        AreaScenario base = AreaScenario.build(subaGraph, ServiceAreas.ASE_5, seed)
                .withSites(SensitiveSites.read(Path.of(args[1])));
        Row baseRow = row("SubaBase-n807-s1 (2022)", "Suba", base, seed,
                base.operation().collectionsPerWeek() / 7.0);

        RoadGraph bogota = RoadGraphFile.read(Path.of(args[2])).graph();
        List<SensitiveSites.Site> sites = SensitiveSites.read(Path.of(args[3]));
        ServiceAreas.ServiceArea area = ServiceAreas.split(List.of(suba)).keySet().iterator().next();
        // Suba no tiene la capa del operador: sus contenedores son los 320 simulados de la cantidad oficial (ADR 0013).
        DoorToDoor.Plan plan = DoorToDoor.plan(bogota, area, suba, Sectors.of(area)::locate,
                DoorToDoor.containers(bogota, area, suba, Map.of(), SideLoading.MEAN_FILL, seed), "");
        List<Row> slots = plan.turns().stream()
                .map(turn -> row(turn.slot().code(), "Bogotá",
                        DoorToDoor.scenario(bogota, area, plan, turn).withSites(sites), seed,
                        DoorToDoor.COLLECTIONS_PER_WEEK / 7.0))
                .toList();

        Path outDir = Path.of(args[4]);
        Files.createDirectories(outDir);
        Path csv = outDir.resolve("suba-live-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("plan,graph,seed,iterations,unload_s,containers,chunks,kg_per_collection,kg_per_day,"
                    + "share_of_locality,trucks,trips,km,vehicle_hours,liters,kg_co2,r02_stops,r07_stops,build_s\n");
            write(out, baseRow, seed, subaKgPerDay);
            for (Row slot : slots) {
                write(out, slot, seed, subaKgPerDay);
            }
        }

        System.out.printf(Locale.ROOT, "Suba: %.1f t/día por población; ALNS con semilla %d y %d iteraciones;"
                + " %d min de descarga%n%n", subaKgPerDay / 1000, seed, AreaPlanner.ITERATIONS,
                BogotaFigures.UNLOAD_SECONDS_LOW / 60);
        System.out.println("| Plan | Grafo | Contenedores | Trozos | t por recolección | t/día | Camiones | Viajes |"
                + " km | Horas de vehículo | t CO₂ | R02 | R07 | Armado |");
        System.out.println("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
        print(baseRow);
        slots.forEach(CompareSubaLive::print);
        double liveKgPerDay = slots.stream().mapToDouble(Row::kgPerDay).sum();
        System.out.printf(Locale.ROOT, "%nt/día de la semana: base %.1f (%.1f %% de Suba), en vivo %.1f (%.1f %%)%n",
                baseRow.kgPerDay() / 1000, 100 * baseRow.kgPerDay() / subaKgPerDay, liveKgPerDay / 1000,
                100 * liveKgPerDay / subaKgPerDay);
        for (String days : List.of("LMV", "MJS")) {
            List<Row> day = slots.stream().filter(slot -> slot.name().startsWith(days)).toList();
            System.out.printf(Locale.ROOT, "Un día %s: %d camiones (día + noche), %d viajes, %.1f km, %.1f h,"
                    + " %.1f t CO₂, %.1f t recogidas%n", days, day.stream().mapToInt(Row::trucks).sum(),
                    day.stream().mapToInt(Row::trips).sum(), day.stream().mapToDouble(r -> r.total().km()).sum(),
                    day.stream().mapToDouble(r -> r.total().hours()).sum(),
                    day.stream().mapToDouble(r -> r.total().kgCo2()).sum() / 1000,
                    day.stream().mapToLong(Row::kg).sum() / 1000.0);
        }
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    private static Row row(String name, String graph, AreaScenario scenario, long seed, double runsPerDay) {
        long start = System.nanoTime();
        AreaPlanner.Fleet fleet = AreaPlanner.fleet(scenario, BogotaFigures.UNLOAD_SECONDS_LOW, seed);
        double seconds = (System.nanoTime() - start) / 1e9;
        Evaluation evaluation = Evaluator.evaluate(scenario.instance(), fleet.solution());
        if (!evaluation.feasible()) {
            throw new IllegalStateException(name + " infactible: " + evaluation.violations());
        }
        DemoPlan plan = fleet.plan();
        PlanReport report = PlanReport.of(plan);
        long kg = plan.routes().stream().mapToLong(DemoPlan.Route::loadKg).sum();
        return new Row(name, graph, plan.containers().size(), plan.streets().size(), kg, kg * runsPerDay,
                plan.routes().size(), plan.routes().stream().mapToInt(route -> route.tripLoadsKg().length).sum(),
                report.total(), count(plan, "R02"), count(plan, "R07"), seconds);
    }

    private static int count(DemoPlan plan, String rule) {
        return plan.compliance().stream().filter(check -> check.id().equals(rule)).mapToInt(Compliance.Check::count)
                .sum();
    }

    private static void write(BufferedWriter out, Row row, long seed, double subaKgPerDay) throws IOException {
        PlanReport.Line total = row.total();
        out.write(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%.0f,%.4f,%d,%d,%.1f,%.1f,%.1f,%.0f,%d,%d,%.1f%n",
                row.name(), row.graph(), seed, AreaPlanner.ITERATIONS, BogotaFigures.UNLOAD_SECONDS_LOW,
                row.containers(), row.chunks(), row.kg(), row.kgPerDay(), row.kgPerDay() / subaKgPerDay, row.trucks(),
                row.trips(), total.km(), total.hours(), total.liters(), total.kgCo2(), row.nightNoise(),
                row.noCompaction(), row.buildSeconds()));
    }

    private static void print(Row row) {
        PlanReport.Line total = row.total();
        System.out.printf(Locale.ROOT, "| %s | %s | %d | %d | %.1f | %.1f | %d | %d | %.1f | %.1f | %.1f | %d | %d |"
                + " %.1f s |%n", row.name(), row.graph(), row.containers(), row.chunks(), row.kg() / 1000.0,
                row.kgPerDay() / 1000, row.trucks(), row.trips(), total.km(), total.hours(), total.kgCo2() / 1000,
                row.nightNoise(), row.noCompaction(), row.buildSeconds());
    }
}
