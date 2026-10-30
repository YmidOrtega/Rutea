package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import me.ymid.rutea.app.planning.AreaPlanner;
import me.ymid.rutea.geo.RoadGraphFile;

/**
 * Exporta en formato CVRPLIB las instancias de cargue lateral de las cinco ASE sobre el grafo de Bogotá, una por turno:
 * las mismas que arma {@code CityPlanner} con las 20 localidades. Así el arnés de benchmarks las mide igual que las del
 * set X y que las de Suba, también contra Timefold desde {@code rutea-bench}.
 *
 * <p>Uso: {@code ExportBogotaInstances <bogota.rgraph> <dir-contenedores> <dir-salida>}
 */
public final class ExportBogotaInstances {

    private ExportBogotaInstances() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("uso: ExportBogotaInstances <bogota.rgraph> <dir-contenedores> <dir-salida>");
            System.exit(2);
        }
        Path graphFile = Path.of(args[0]);
        Path containers = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        if (!Files.exists(graphFile)) {
            System.err.println("No existe " + graphFile + ". Constrúyelo con: mvn -pl rutea-geo compile exec:java@bogota");
            System.exit(1);
        }
        RoadGraphFile.Stored stored = RoadGraphFile.read(graphFile);
        Files.createDirectories(outDir);
        long seed = AreaPlanner.DEFAULT_SEED;
        double fill = SideLoading.MEAN_FILL;
        for (SideLoading.Area sideLoading : SideLoading.AREAS) {
            ServiceAreas.ServiceArea area = sideLoading.area();
            for (SideLoading.Shift shift : sideLoading.shifts()) {
                String when = String.format(Locale.ROOT, "turno de %s desde las %s, %d h",
                        shift.name().equals("Dia") ? "día" : shift.name().toLowerCase(Locale.ROOT), shift.start(),
                        shift.seconds() / 3600);
                String load = String.format(Locale.ROOT, "carga por capacidad con llenado del %.0f %%", fill * 100);
                if (sideLoading.planned().isPresent()) {
                    SideLoading.Planned planned = sideLoading.planned().get();
                    ExportAreaInstance.write(stored, AreaScenario.buildPlanned(stored.graph(), area, shift,
                            planned.count(), planned.liters(), fill, seed), outDir, String.format(Locale.ROOT,
                            "los %d contenedores negros de la cantidad oficial, con ubicación simulada (semilla %d) y"
                                    + " un tamaño al azar entre %s L; %s; %s", planned.count(), seed, planned.liters(),
                            when, load));
                    continue;
                }
                OperatorContainers.Layer layer = OperatorContainers.LAYERS.stream()
                        .filter(candidate -> candidate.area() == area.number()).findFirst().orElseThrow();
                Path csv = containers.resolve(layer.file());
                List<OperatorContainers.Container> real = OperatorContainers.read(csv);
                ExportAreaInstance.write(stored, AreaScenario.buildReal(stored.graph(), area, shift, real, fill),
                        outDir, String.format(Locale.ROOT, "contenedores negros \"en campo\" de la capa pública del"
                                + " operador asignados al turno (%s, SHA-256 %s); %s; %s", layer.file(), sha256(csv),
                                when, load));
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
