package me.ymid.rutea.app.scenario;

import java.util.List;

import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.AreaSpec;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Instance;

/**
 * La operación de Suba traducida a CVRP: el nodo 0 es la portería de Doña Juana y el nodo {@code i} es el
 * contenedor {@code containers.get(i - 1)}. Cada ruta es un viaje que sale del relleno, recoge hasta llenar
 * el compactador y vuelve a descargar.
 *
 * @param points vértice del grafo de cada nodo de la instancia
 */
public record SubaScenario(RoadGraph graph, int gateVertex, int depotVertex, List<Container> containers,
        int[] points, Instance instance) {

    public SubaScenario {
        containers = List.copyOf(containers);
    }

    /** Con los sitios de {@link AreaSpec#SUBA} y las cifras de {@link SubaFigures}. */
    public static SubaScenario build(RoadGraph graph, long seed) {
        int count = SubaFigures.nonRecyclableContainers();
        return build(graph, siteNode(AreaSpec.DISPOSAL), siteNode(AreaSpec.DEPOT), count,
                SubaFigures.meanKgPerCollection(count), seed);
    }

    static SubaScenario build(RoadGraph graph, long gateNode, long depotNode, int count, double meanKg, long seed) {
        int gate = vertex(graph, gateNode, "la portería del relleno");
        int depot = vertex(graph, depotNode, "la base");
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
        Instance instance = new Instance(name, SubaFigures.COMPACTOR_CAPACITY_KG, demands,
                DistanceMatrix.of(matrix.metersRows()));
        return new SubaScenario(graph, gate, depot, containers, points, instance);
    }

    private static long siteNode(String name) {
        return AreaSpec.SUBA.sites().stream().filter(site -> site.name().equals(name)).findFirst().orElseThrow()
                .osmNode();
    }

    private static int vertex(RoadGraph graph, long osmNode, String what) {
        return graph.vertexOf(osmNode).orElseThrow(() -> new IllegalStateException(
                "el grafo no tiene " + what + " (nodo " + osmNode + "); reconstrúyelo"));
    }
}
