package me.ymid.rutea.app.scenario;

import java.util.Arrays;
import java.util.List;

import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.AreaSpec;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Instance;

/**
 * La operación de Suba traducida a CVRP. Cada ruta es el turno de un compactador: sale de la base de Fontibón,
 * recoge hasta llenarse y termina en la portería de Doña Juana a descargar. El nodo {@code i} es el contenedor
 * {@code containers.get(i - 1)}; el nodo 0 es dos lugares a la vez, y la matriz asimétrica lo permite: su fila
 * sale de la base y su columna llega a la portería. El regreso de la portería a la base es igual para todos los
 * camiones, así que no cambia la optimización: va en {@code returnMeters}.
 *
 * @param points vértice del grafo de cada nodo de la instancia; el del nodo 0 es la base
 * @param travel metros y segundos entre todos los lugares: 0 es la base, {@code i} el nodo {@code i} y
 *     {@link #gate()} la portería
 */
public record SubaScenario(RoadGraph graph, int gateVertex, int depotVertex, List<Container> containers,
        int[] points, Instance instance, TravelMatrix travel) {

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
        points[0] = depot;
        for (int i = 0; i < count; i++) {
            points[i + 1] = containers.get(i).vertex();
            demands[i + 1] = containers.get(i).kg();
        }
        // La matriz se calcula con la portería al final: [base, contenedores..., portería].
        int[] withGate = Arrays.copyOf(points, count + 2);
        withGate[count + 1] = gate;
        TravelMatrix matrix = TravelMatrix.compute(graph, withGate, Runtime.getRuntime().availableProcessors());
        int[][] distances = new int[count + 1][count + 1];
        for (int i = 0; i <= count; i++) {
            for (int j = 0; j <= count; j++) {
                distances[i][j] = i == j ? 0 : matrix.meters(i, j == 0 ? count + 1 : j);
            }
        }
        // El sufijo -n<nodos> es el mismo de las instancias X: el arnés ordena por él.
        String name = "SubaBase-n" + (count + 1) + "-s" + seed;
        Instance instance = new Instance(name, SubaFigures.COMPACTOR_CAPACITY_KG, demands, DistanceMatrix.of(distances));
        return new SubaScenario(graph, gate, depot, containers, points, instance, matrix);
    }

    /** Índice de la portería en {@link #travel}; la base es 0 y el contenedor del nodo {@code i}, {@code i}. */
    public int gate() {
        return containers.size() + 1;
    }

    /** Metros de la portería a la base: el regreso de cada camión al final del turno. */
    public int returnMeters() {
        return travel.meters(gate(), 0);
    }

    public int returnSeconds() {
        return travel.seconds(gate(), 0);
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
