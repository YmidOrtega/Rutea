package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.AreaSpec;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.cvrplib.CvrplibWriter;

/**
 * Exporta la instancia real de Suba en formato CVRPLIB para que el arnés de benchmarks la mida igual que
 * las del set X, incluso contra Timefold desde {@code rutea-bench}, que no puede depender de este módulo.
 *
 * <p>El depósito (nodo 1 del {@code .vrp}) es la portería de Doña Juana: cada viaje sale del relleno, recoge
 * hasta llenar el compactador y vuelve a descargar. El tramo desde y hacia la base no entra. Las distancias
 * son metros por la red vial, asimétricas.
 *
 * <p>Uso: {@code ExportSubaInstance <grafo.rgraph> <dir-salida> [--seed <n>]}
 */
public final class ExportSubaInstance {

    private ExportSubaInstance() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && !(args.length == 4 && args[2].equals("--seed"))) {
            System.err.println("uso: ExportSubaInstance <grafo.rgraph> <dir-salida> [--seed <n>]");
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
        Instance instance = build(stored.graph(), seed);
        Files.createDirectories(outDir);
        Path file = outDir.resolve(instance.name() + ".vrp");
        String comment = String.format(Locale.ROOT, "Suba (ASE 5): portería de Doña Juana como depósito y %d"
                + " contenedores negros simulados (semilla %d); distancias en metros por la red vial del extracto OSM"
                + " con SHA-256 %s; cifras y fuentes en docs/datos.md. Datos © colaboradores de OpenStreetMap,"
                + " ODbL 1.0", instance.customerCount(), seed, RoadGraphFile.hex(stored.sourceSha256()));
        CvrplibWriter.writeExplicit(file, instance, comment);
        System.out.printf(Locale.ROOT, "%s: %d nodos, capacidad %d kg, %d kg en total (%,d bytes)%n", file,
                instance.size(), instance.capacity(), totalDemand(instance), Files.size(file));
    }

    public static Instance build(RoadGraph graph, long seed) {
        long gateNode = AreaSpec.SUBA.sites().stream().filter(site -> site.name().equals(AreaSpec.DISPOSAL))
                .findFirst().orElseThrow().osmNode();
        int count = SubaFigures.nonRecyclableContainers();
        return build(graph, gateNode, count, SubaFigures.meanKgPerCollection(count), seed);
    }

    static Instance build(RoadGraph graph, long gateNode, int count, double meanKg, long seed) {
        int gate = graph.vertexOf(gateNode).orElseThrow(() -> new IllegalStateException(
                "el grafo no tiene la portería del relleno (nodo " + gateNode + "); reconstrúyelo"));
        List<Container> containers = SimulatedContainers.place(graph, WasteStream.NON_RECYCLABLE, count, meanKg, seed);
        int[] points = new int[count + 1];
        int[] demands = new int[count + 1];
        points[0] = gate;
        for (int i = 0; i < count; i++) {
            points[i + 1] = containers.get(i).vertex();
            demands[i + 1] = containers.get(i).kg();
        }
        TravelMatrix matrix = TravelMatrix.compute(graph, points, Runtime.getRuntime().availableProcessors());
        // El sufijo -n<nodos> es el mismo de las instancias X: el arnés ordena por él.
        String name = "Suba-n" + (count + 1) + "-s" + seed;
        return new Instance(name, SubaFigures.COMPACTOR_CAPACITY_KG, demands, DistanceMatrix.of(matrix.metersRows()));
    }

    private static long totalDemand(Instance instance) {
        long total = 0;
        for (int node = 1; node < instance.size(); node++) {
            total += instance.demand(node);
        }
        return total;
    }
}
