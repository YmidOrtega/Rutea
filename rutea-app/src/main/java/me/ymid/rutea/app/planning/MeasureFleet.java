package me.ymid.rutea.app.planning;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;

/**
 * La flota de cargue lateral de cada ASE con las 20 localidades, frente a la que declara su operador: con cada llenado
 * de {@link SideLoading#FILLS} y las dos descargas del rango publicado. ALNS con límite de iteraciones: el resultado
 * depende solo de la semilla.
 *
 * <p>Uso: {@code MeasureFleet <bogota.rgraph> <dir-extracto> <dir-salida>}
 */
public final class MeasureFleet {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private static final int[] UNLOADS = {BogotaFigures.UNLOAD_SECONDS_LOW, BogotaFigures.UNLOAD_SECONDS_HIGH};

    private MeasureFleet() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("uso: MeasureFleet <bogota.rgraph> <dir-extracto> <dir-salida>");
            System.exit(2);
        }
        RoadGraph bogota = RoadGraphFile.read(Path.of(args[0])).graph();
        Map<Integer, List<OperatorContainers.Container>> real = new LinkedHashMap<>();
        for (OperatorContainers.Layer layer : OperatorContainers.LAYERS) {
            real.put(layer.area(), OperatorContainers.read(Path.of(args[1]).resolve(layer.file())));
        }
        List<Locality> all = new ArrayList<>();
        ServiceAreas.BOGOTA.forEach(area -> all.addAll(area.localities()));
        long seed = AreaPlanner.DEFAULT_SEED;
        long iterations = AreaPlanner.ITERATIONS;

        Path outDir = Path.of(args[2]);
        Files.createDirectories(outDir);
        Path csv = outDir.resolve("fleet-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        // Por ASE: "camiones (cota)" de cada llenado y descarga.
        Map<String, List<String>> cells = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        System.out.printf(Locale.ROOT, "Las 20 localidades: ALNS con semilla %d y %d iteraciones%n%n", seed, iterations);
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("fill,unload_s,area,shift,instance,trips,trucks,trucks_lower_bound,fewest_proven,schedule_ms,"
                    + "shared_across_shifts,declared,beyond_declared,seed,iterations\n");
            for (double fill : SideLoading.FILLS) {
                CityPlanner.Plan plan = CityPlanner.plan(bogota, real, all, fill, seed, iterations, true);
                for (int unload : UNLOADS) {
                    Map<ServiceAreas.ServiceArea, List<CityPlanner.AreaPlan>> byArea = new LinkedHashMap<>();
                    Map<CityPlanner.AreaPlan, ShiftScheduler.Schedule> schedules = new LinkedHashMap<>();
                    for (CityPlanner.AreaPlan p : plan.areas()) {
                        long start = System.nanoTime();
                        ShiftScheduler.Schedule schedule = AreaPlanner.fewestTrucks(p.scenario(), p.solution(), unload);
                        double ms = (System.nanoTime() - start) / 1e6;
                        schedules.put(p, schedule);
                        byArea.computeIfAbsent(p.scenario().area(), a -> new ArrayList<>()).add(p);
                        out.write(String.format(Locale.ROOT, "%.2f,%d,%d,%s,%s,%d,%d,%d,%b,%.1f,,,,%d,%d%n", fill,
                                unload, p.scenario().area().number(), p.shift().name(),
                                p.scenario().instance().name(), p.solution().routeCount(), schedule.trucks().size(),
                                schedule.lowerBound(), schedule.fewestTrucks(), ms, seed, iterations));
                    }
                    for (Map.Entry<ServiceAreas.ServiceArea, List<CityPlanner.AreaPlan>> entry : byArea.entrySet()) {
                        List<CityPlanner.AreaPlan> plans = entry.getValue();
                        CityPlanner.Fleet fleet = CityPlanner.Fleet.of(entry.getKey(),
                                plans.stream().map(CityPlanner.AreaPlan::shift).toList(),
                                plans.stream().map(schedules::get).toList());
                        out.write(String.format(Locale.ROOT, "%.2f,%d,%d,,,,%d,%d,%b,,%b,%s,%d,%d,%d%n", fill, unload,
                                fleet.area().number(), fleet.trucks(), fleet.lowerBound(),
                                fleet.trucks() == fleet.lowerBound(), fleet.sharedAcrossShifts(),
                                fleet.declared().isPresent() ? String.valueOf(fleet.declared().getAsInt()) : "",
                                fleet.beyondDeclared(), seed, iterations));
                        cells.computeIfAbsent(label(fleet), k -> new ArrayList<>()).add(fleet.trucks()
                                + (fleet.trucks() == fleet.lowerBound() ? "" : " (" + fleet.lowerBound() + ")"));
                        fleet.warning().ifPresent(w -> warnings.add(String.format(Locale.ROOT,
                                "llenado del %.0f %%, descarga de %d min: %s", fill * 100, unload / 60, w)));
                    }
                }
            }
        }
        System.out.println("| ASE | Declarada | 50 %, 40 min | 50 %, 90 min | 75 %, 40 min | 75 %, 90 min |"
                + " 100 %, 40 min | 100 %, 90 min |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|");
        cells.forEach((label, values) -> System.out.println("| " + label + " | " + String.join(" | ", values) + " |"));
        System.out.println();
        warnings.forEach(w -> System.out.println("Aviso, " + w));
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    private static String label(CityPlanner.Fleet fleet) {
        return fleet.area().number() + " | "
                + (fleet.declared().isPresent() ? String.valueOf(fleet.declared().getAsInt()) : "–");
    }
}
