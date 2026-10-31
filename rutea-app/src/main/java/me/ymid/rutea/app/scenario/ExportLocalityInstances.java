package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.ymid.rutea.app.planning.AreaPlanner;
import me.ymid.rutea.app.planning.Sectors;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.ServiceAreas.ServiceArea;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.cvrplib.CvrplibWriter;

public final class ExportLocalityInstances {

    private ExportLocalityInstances() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("uso: ExportLocalityInstances <bogota.rgraph> <dir-contenedores> <dir-salida>"
                    + " [n.º de localidad...]");
            System.exit(2);
        }
        Path graphFile = Path.of(args[0]);
        Path containersDir = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        if (!Files.exists(graphFile)) {
            System.err.println("No existe " + graphFile + ". Constrúyelo con: mvn -pl rutea-geo compile exec:java@bogota");
            System.exit(1);
        }
        List<Locality> localities = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            int number = Integer.parseInt(args[i]);
            localities.add(ServiceAreas.locality(number)
                    .orElseThrow(() -> new IllegalArgumentException("no hay localidad " + number)));
        }
        if (localities.isEmpty()) {
            localities.add(ServiceAreas.locality(11).orElseThrow());
        }
        RoadGraphFile.Stored stored = RoadGraphFile.read(graphFile);
        Files.createDirectories(outDir);
        long seed = AreaPlanner.DEFAULT_SEED;
        double fill = SideLoading.MEAN_FILL;
        for (Locality locality : localities) {
            ServiceArea area = ServiceAreas.split(List.of(locality)).keySet().iterator().next();
            SideLoading.Area sideLoading = SideLoading.of(area);
            Map<SideLoading.Shift, List<Container>> containers = new LinkedHashMap<>();
            String suffix;
            String origin;
            if (sideLoading.planned().isPresent()) {
                SideLoading.Planned planned = sideLoading.planned().get();
                containers.put(sideLoading.shifts().getFirst(), AreaScenario.plannedContainers(stored.graph(), area,
                        List.of(locality), planned.count(), planned.liters(), fill, seed));
                suffix = String.format(Locale.ROOT, "-s%d-f%d", seed, Math.round(fill * 100));
                origin = String.format(Locale.ROOT, "simulados de la cantidad oficial de %s (semilla %d)",
                        area.name(), seed);
            } else {
                OperatorContainers.Layer layer = OperatorContainers.LAYERS.stream()
                        .filter(candidate -> candidate.area() == area.number()).findFirst().orElseThrow();
                List<OperatorContainers.Container> real = OperatorContainers.read(containersDir.resolve(layer.file()));
                for (SideLoading.Shift shift : sideLoading.shifts()) {
                    containers.put(shift, AreaScenario.realContainers(stored.graph(), area, shift, List.of(locality),
                            real, fill));
                }
                suffix = String.format(Locale.ROOT, "-f%d", Math.round(fill * 100));
                origin = "\"en campo\" de la capa pública del operador (" + layer.file() + ")";
            }
            DoorToDoor.Plan plan = DoorToDoor.plan(stored.graph(), area, locality, Sectors.of(area)::locate,
                    containers, suffix);
            System.out.printf(Locale.ROOT, "%s: %.1f t/día, %.1f de ellas en %d contenedores; %.3f kg por metro de"
                    + " calle local%n", locality.name(), plan.kgPerDay() / 1000, plan.containerKgPerDay() / 1000,
                    containers.values().stream().mapToInt(List::size).sum(), plan.kgPerMeter());
            for (DoorToDoor.Turn turn : plan.turns()) {
                write(stored, area, plan, turn, origin, fill, outDir);
            }
        }
    }

    private static void write(RoadGraphFile.Stored stored, ServiceArea area, DoorToDoor.Plan plan, DoorToDoor.Turn turn,
            String origin, double fill, Path outDir) throws IOException {
        Instance instance = turn.instance();
        long chunkMeters = turn.chunks().stream().mapToLong(StreetChunks.Chunk::meters).sum();
        List<String> sectors = new ArrayList<>();
        for (Map.Entry<String, DoorToDoor.Slot> entry : plan.calendar().entrySet()) {
            if (entry.getValue().equals(turn.slot())) {
                sectors.add(entry.getKey());
            }
        }
        // Los códigos de UPZ son números: 2 va antes que 17, y "rural" al final.
        sectors.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        String comment = String.format(Locale.ROOT, "%s (ASE %d), %s: %d contenedores negros %s con carga por"
                + " capacidad y llenado del %.0f %%, y %d trozos de calle local de hasta %d m (%.1f km) para el puerta"
                + " a puerta (ADR 0014), los de las UPZ %s; cada trozo carga %.3f kg por metro, lo que le toca a la"
                + " localidad por población (%.1f t/día) menos lo de sus contenedores (%.1f t/día), por los días entre"
                + " %d pasadas por semana (PENDIENTE_VERIFICAR); llegar a un trozo incluye recorrerlo recogiendo; el"
                + " depósito sale de la base de %s (fila 1) y llega a la portería de Doña Juana (columna 1); el regreso"
                + " a la base no entra; distancias en metros por la red vial del extracto OSM con SHA-256 %s; cifras y"
                + " fuentes en docs/datos.md. Datos © colaboradores de OpenStreetMap, ODbL 1.0",
                plan.locality().name(), area.number(), turn.slot().label(), turn.containers().size(), origin,
                fill * 100, turn.chunks().size(), DoorToDoor.MAX_CHUNK_METERS, chunkMeters / 1000.0,
                String.join(", ", sectors), plan.kgPerMeter(), plan.kgPerDay() / 1000,
                plan.containerKgPerDay() / 1000, DoorToDoor.COLLECTIONS_PER_WEEK, area.base().locality(),
                RoadGraphFile.hex(stored.sourceSha256()));
        Path file = outDir.resolve(instance.name() + ".vrp");
        CvrplibWriter.writeExplicit(file, instance, comment);
        long total = 0;
        for (int node = 1; node < instance.size(); node++) {
            total += instance.demand(node);
        }
        System.out.printf(Locale.ROOT, "  %s: %d contenedores, %d trozos (%.1f km), %.1f t, matriz en %d ms, %,d"
                + " bytes%n", file, turn.containers().size(), turn.chunks().size(), chunkMeters / 1000.0, total / 1000.0,
                turn.matrixNanos() / 1_000_000, Files.size(file));
    }
}
