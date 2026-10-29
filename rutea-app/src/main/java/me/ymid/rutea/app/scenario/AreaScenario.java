package me.ymid.rutea.app.scenario;

import java.util.Arrays;
import java.util.List;

import me.ymid.rutea.app.scenario.ServiceAreas.Operation;
import me.ymid.rutea.app.scenario.ServiceAreas.ServiceArea;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Instance;

/**
 * La operación de una ASE traducida a CVRP. Cada ruta es el turno de un compactador: sale de la base del
 * operador, recoge hasta llenarse y termina en la portería de Doña Juana a descargar. El nodo {@code i} es el contenedor
 * {@code containers.get(i - 1)}; el nodo 0 es dos lugares a la vez, y la matriz asimétrica lo permite: su fila
 * sale de la base y su columna llega a la portería. El regreso de la portería a la base es igual para todos los
 * camiones, así que no cambia la optimización: va en {@code returnMeters}.
 *
 * @param area la ASE, con la operación de su operador
 * @param points vértice del grafo de cada nodo de la instancia; el del nodo 0 es la base
 * @param travel metros y segundos entre todos los lugares: 0 es la base, {@code i} el nodo {@code i} y
 *     {@link #gate()} la portería
 * @param sites sitios sensibles para R02 y R07; vacío si no se cargaron
 */
public record AreaScenario(ServiceArea area, RoadGraph graph, int gateVertex, int depotVertex,
        List<Container> containers, int[] points, Instance instance, TravelMatrix travel, List<SensitiveSites.Site> sites) {

    public AreaScenario {
        operationOf(area);
        containers = List.copyOf(containers);
        sites = List.copyOf(sites);
    }

    public AreaScenario withSites(List<SensitiveSites.Site> sensitive) {
        return new AreaScenario(area, graph, gateVertex, depotVertex, containers, points, instance, travel, sensitive);
    }

    /** Un contenedor aislado por un cierre queda a {@link TravelMatrix#UNREACHABLE}. */
    public AreaScenario withTravel(TravelMatrix matrix) {
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
        return new AreaScenario(area, matrix.graph(), gateVertex, depotVertex, containers, points, closed, matrix,
                sites);
    }

    /** Con la base y las cifras del operador de la ASE, que tiene que tener la operación verificada. */
    public static AreaScenario build(RoadGraph graph, ServiceArea area, long seed) {
        Operation operation = operationOf(area);
        int count = operation.nonRecyclableContainers();
        return build(graph, area, ServiceAreas.DISPOSAL_OSM_NODE, operation.base().osmNode(), count,
                operation.meanKgPerCollection(count), seed);
    }

    static AreaScenario build(RoadGraph graph, ServiceArea area, long gateNode, long depotNode, int count,
            double meanKg, long seed) {
        return build(graph, area, WasteStream.NON_RECYCLABLE, vertex(graph, depotNode, "la base"),
                vertex(graph, gateNode, "la portería del relleno"), operationOf(area).compactorKg(), count, meanKg,
                seed, prefix(area) + "Base");
    }

    /**
     * Aprovechables (R10): una organización de recicladores sale de la ECA, recoge los contenedores blancos y
     * vuelve a la misma ECA a descargar, en vehículos sin compactación (R06). La ECA es un supuesto (ver
     * {@link BogotaFigures#ecaVertex}); los contenedores se ubican con otra semilla derivada para no caer encima de
     * los negros.
     */
    public static AreaScenario buildRecyclable(RoadGraph graph, ServiceArea area, long seed) {
        Operation operation = operationOf(area);
        int count = operation.recyclableContainers();
        int eca = BogotaFigures.ecaVertex(graph);
        return build(graph, area, WasteStream.RECYCLABLE, eca, eca, BogotaFigures.RECYCLABLE_VEHICLE_KG, count,
                operation.meanRecyclableKgPerCollection(count), seed * 31 + 17, prefix(area) + "Reciclaje");
    }

    static AreaScenario build(RoadGraph graph, ServiceArea area, WasteStream stream, int depot, int gate,
            int capacityKg, int count, double meanKg, long seed, String namePrefix) {
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
        return new AreaScenario(area, graph, gate, depot, containers, points, instance, matrix, List.of());
    }

    public Operation operation() {
        return area.operation().orElseThrow();
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

    private static Operation operationOf(ServiceArea area) {
        return area.operation().orElseThrow(() -> new IllegalArgumentException("la ASE " + area.number()
                + " no tiene la operación del operador (base y cifras): está PENDIENTE_VERIFICAR en docs/datos.md"));
    }

    /** Prefijo del nombre de la instancia: "Suba" da {@code SubaBase-n807-s1}. */
    private static String prefix(ServiceArea area) {
        return area.name().replace(" ", "");
    }

    private static int vertex(RoadGraph graph, long osmNode, String what) {
        return graph.vertexOf(osmNode).orElseThrow(() -> new IllegalStateException(
                "el grafo no tiene " + what + " (nodo " + osmNode + "); reconstrúyelo"));
    }
}
