package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.cvrplib.CvrplibWriter;

/**
 * Exporta la instancia real de una ASE en formato CVRPLIB para que el arnés de benchmarks la mida igual que
 * las del set X, incluso contra Timefold desde {@code rutea-bench}, que no puede depender de este módulo.
 *
 * <p>Cada ruta sale de la base del operador y termina en la portería de Doña Juana: la fila del depósito (nodo 1
 * del {@code .vrp}) son distancias desde la base y su columna, distancias hasta la portería. El regreso a la base
 * es igual para todos los camiones y no entra. Las distancias son metros por la red vial, asimétricas.
 *
 * <p>Uso: {@code ExportAreaInstance <grafo.rgraph> <dir-salida> [--seed <n>]}
 */
public final class ExportAreaInstance {

    private ExportAreaInstance() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && !(args.length == 4 && args[2].equals("--seed"))) {
            System.err.println("uso: ExportAreaInstance <grafo.rgraph> <dir-salida> [--seed <n>]");
            System.exit(2);
        }
        Path graphFile = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        long seed = args.length == 4 ? Long.parseLong(args[3]) : 1;
        if (!Files.exists(graphFile)) {
            System.err.println("No existe " + graphFile + ". Constrúyelo con: mvn -pl rutea-geo compile exec:java");
            System.exit(1);
        }

        RoadGraphFile.Stored stored = RoadGraphFile.read(graphFile);
        Files.createDirectories(outDir);
        AreaScenario frozen = AreaScenario.build(stored.graph(), ServiceAreas.ASE_5, seed);
        // El texto de la instancia congelada no cambia: sus bytes son la referencia del arnés (ADR 0013).
        write(stored, frozen, outDir, String.format(Locale.ROOT, "%d contenedores negros simulados (semilla %d)",
                frozen.instance().customerCount(), seed));
        List<Integer> liters = ServiceAreas.ASE_5.verified().containerLiters();
        AreaScenario planned = AreaScenario.buildPlanned(stored.graph(), ServiceAreas.ASE_5, SideLoading.ASE_5_NIGHT,
                SideLoading.ASE_5_CONTAINERS, liters, SideLoading.MEAN_FILL, seed);
        write(stored, planned, outDir, String.format(Locale.ROOT, "los %d contenedores negros del Plan de"
                + " Contenerización de 2026, con ubicación simulada (semilla %d) y un tamaño al azar entre %s L; carga"
                + " por capacidad con llenado del %.0f %%", SideLoading.ASE_5_CONTAINERS, seed, liters,
                SideLoading.MEAN_FILL * 100));
    }

    private static void write(RoadGraphFile.Stored stored, AreaScenario scenario, Path outDir, String containers)
            throws IOException {
        Instance instance = scenario.instance();
        Path file = outDir.resolve(instance.name() + ".vrp");
        String comment = String.format(Locale.ROOT, "%s (ASE %d): %s; el depósito sale de la base de %s (fila 1) y"
                + " llega a la portería de Doña Juana (columna 1); el regreso a la base (%d m por camión) no entra;"
                + " distancias en metros por la red vial del extracto OSM con SHA-256 %s; cifras y fuentes en"
                + " docs/datos.md. Datos © colaboradores de OpenStreetMap, ODbL 1.0", scenario.area().name(),
                scenario.area().number(), containers, scenario.area().base().locality(), scenario.returnMeters(),
                RoadGraphFile.hex(stored.sourceSha256()));
        CvrplibWriter.writeExplicit(file, instance, comment);
        System.out.printf(Locale.ROOT, "%s: %d nodos, capacidad %d kg, %d kg en total (%,d bytes)%n", file,
                instance.size(), instance.capacity(), totalDemand(instance), Files.size(file));
    }

    private static long totalDemand(Instance instance) {
        long total = 0;
        for (int node = 1; node < instance.size(); node++) {
            total += instance.demand(node);
        }
        return total;
    }
}
