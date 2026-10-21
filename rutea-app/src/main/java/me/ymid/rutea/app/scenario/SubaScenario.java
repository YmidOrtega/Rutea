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
 * @param sites sitios sensibles para R02 y R07; vacío si no se cargaron
 */
public record SubaScenario(RoadGraph graph, int gateVertex, int depotVertex, List<Container> containers,
        int[] points, Instance instance, TravelMatrix travel, List<SensitiveSites.Site> sites) {

    public SubaScenario {
        containers = List.copyOf(containers);
        sites = List.copyOf(sites);
    }

    public SubaScenario withSites(List<SensitiveSites.Site> sensitive) {
        return new SubaScenario(graph, gateVertex, depotVertex, containers, points, instance, travel, sensitive);
    }

    /** Un contenedor aislado por un cierre queda a {@link TravelMatrix#UNREACHABLE}. */
    public SubaScenario withTravel(TravelMatrix matrix) {
        int n = instance.size();
        int[][] distances = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                distances[i][j] = i == j ? 0 : matrix.meters(i, j == 0 ? gate() : j);
            }
        }
        int[] demands = new int[n];
        for (int i = 1; i < n; i++) {
            demands[i] = instance.demand(i);
        }
        Instance closed = new Instance(instance.name(), instance.capacity(), demands, DistanceMatrix.of(distances));
        return new SubaScenario(matrix.graph(), gateVertex, depotVertex, containers, points, closed, matrix, sites);
    }

    /** Con los sitios de {@link AreaSpec#SUBA} y las cifras de {@link SubaFigures}. */
    public static SubaScenario build(RoadGraph graph, long seed) {
        int count = SubaFigures.nonRecyclableContainers();
        return build(graph, siteNode(AreaSpec.DISPOSAL), siteNode(AreaSpec.DEPOT), count,
                SubaFigures.meanKgPerCollection(count), seed);
    }

    static SubaScenario build(RoadGraph graph, long gateNode, long depotNode, int count, double meanKg, long seed) {
        return build(graph, WasteStream.NON_RECYCLABLE, vertex(graph, depotNode, "la base"),
                vertex(graph, gateNode, "la portería del relleno"), SubaFigures.COMPACTOR_CAPACITY_KG, count, meanKg,
                seed, "SubaBase");
    }

    /**
     * Aprovechables (R10): una organización de recicladores sale de la ECA, recoge los contenedores blancos y
     * vuelve a la misma ECA a descargar, en vehículos sin compactación (R06). La ECA es un supuesto (ver
     * {@link SubaFigures#ecaVertex}); los contenedores se ubican con otra semilla derivada para no caer encima de
     * los negros.
     */
    public static SubaScenario buildRecyclable(RoadGraph graph, long seed) {
        int count = SubaFigures.recyclableContainers();
        int eca = SubaFigures.ecaVertex(graph);
        return build(graph, WasteStream.RECYCLABLE, eca, eca, SubaFigures.RECYCLABLE_VEHICLE_KG, count,
                SubaFigures.meanRecyclableKgPerCollection(count), seed * 31 + 17, "SubaReciclaje");
    }

    static SubaScenario build(RoadGraph graph, WasteStream stream, int depot, int gate, int capacityKg, int count,
            double meanKg, long seed, String namePrefix) {
        List<Container> containers = SimulatedContainers.place(graph, stream, count, meanKg, seed);
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
        String name = namePrefix + "-n" + (count + 1) + "-s" + seed;
        Instance instance = new Instance(name, capacityKg, demands, DistanceMatrix.of(distances));
        return new SubaScenario(graph, gate, depot, containers, points, instance, matrix, List.of());
    }

    public WasteStream stream() {
        return containers.getFirst().stream();
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
