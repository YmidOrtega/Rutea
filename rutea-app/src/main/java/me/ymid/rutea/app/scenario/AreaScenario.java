package me.ymid.rutea.app.scenario;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
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
        area.verified();
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
        return build(graph, area, area.localities(), seed);
    }

    /**
     * Solo los contenedores de las localidades elegidas de la ASE. Los contenedores se ubican siempre sobre la ASE
     * completa, con la misma semilla, y la selección solo filtra: un contenedor queda en el mismo lugar, con la misma
     * carga y el mismo id, se elija lo que se elija.
     */
    public static AreaScenario build(RoadGraph graph, ServiceArea area, Collection<Locality> selection, long seed) {
        return build(graph, area, selection, Localities.bogota(), seed);
    }

    static AreaScenario build(RoadGraph graph, ServiceArea area, Collection<Locality> selection,
            Localities localities, long seed) {
        int count = area.nonRecyclableContainers();
        return build(graph, area, selection, localities, ServiceAreas.DISPOSAL_OSM_NODE, area.base().osmNode(),
                count, area.meanKgPerCollection(count), seed);
    }

    static AreaScenario build(RoadGraph graph, ServiceArea area, Collection<Locality> selection,
            Localities localities, long gateNode, long depotNode, int count, double meanKg, long seed) {
        return build(graph, area, selection, localities, WasteStream.NON_RECYCLABLE,
                vertex(graph, depotNode, "la base"), vertex(graph, gateNode, "la portería del relleno"),
                area.verified().compactorKg(), count, meanKg, seed, "Base");
    }

    /**
     * Aprovechables (R10): una organización de recicladores sale de la ECA, recoge los contenedores blancos y
     * vuelve a la misma ECA a descargar, en vehículos sin compactación (R06). La ECA es un supuesto (ver
     * {@link BogotaFigures#ecaVertex}); los contenedores se ubican con otra semilla derivada para no caer encima de
     * los negros.
     */
    public static AreaScenario buildRecyclable(RoadGraph graph, ServiceArea area, long seed) {
        return buildRecyclable(graph, area, area.localities(), seed);
    }

    public static AreaScenario buildRecyclable(RoadGraph graph, ServiceArea area, Collection<Locality> selection,
            long seed) {
        Localities localities = Localities.bogota();
        int count = area.recyclableContainers();
        int eca = BogotaFigures.ecaVertex(graph, streets(graph, area, localities));
        return build(graph, area, selection, localities, WasteStream.RECYCLABLE, eca, eca,
                BogotaFigures.RECYCLABLE_VEHICLE_KG, count, area.meanRecyclableKgPerCollection(count), seed * 31 + 17,
                "Reciclaje");
    }

    static AreaScenario build(RoadGraph graph, ServiceArea area, Collection<Locality> selection,
            Localities localities, WasteStream stream, int depot, int gate, int capacityKg, int count, double meanKg,
            long seed, String kind) {
        Set<Locality> chosen = chosen(area, selection);
        List<Container> containers = new ArrayList<>();
        for (Container container : SimulatedContainers.place(graph, streets(graph, area, localities), stream, count,
                meanKg, seed)) {
            if (chosen.contains(localities.ofShape(graph, container.shape()))) {
                containers.add(container);
            }
        }
        if (containers.isEmpty()) {
            throw new IllegalArgumentException("ningún contenedor cae en " + names(chosen)
                    + ": en el suelo rural no se ubican contenedores");
        }
        count = containers.size();
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
        String prefix = chosen.size() == area.localities().size() ? area.name() : names(chosen);
        String name = prefix.replace(" ", "") + kind + "-n" + (count + 1) + "-s" + seed;
        Instance instance = new Instance(name, capacityKg, demands, DistanceMatrix.of(distances));
        return new AreaScenario(area, graph, gate, depot, containers, points, instance, matrix, List.of());
    }

    /**
     * Las calles donde la ASE pone contenedores: las de sus localidades, sin el suelo rural de Sumapaz. El resto del
     * suelo rural del Distrito (en Usme o Ciudad Bolívar, por ejemplo) no se distingue todavía: los límites de OSM son
     * los de la localidad, no el perímetro urbano.
     */
    public static IntPredicate streets(RoadGraph graph, ServiceArea area, Localities localities) {
        Set<Locality> urban = new HashSet<>();
        area.localities().stream().filter(locality -> !locality.rural()).forEach(urban::add);
        return shape -> graph.shapeInLocality(shape) && urban.contains(localities.ofShape(graph, shape));
    }

    private static Set<Locality> chosen(ServiceArea area, Collection<Locality> selection) {
        if (selection.isEmpty()) {
            throw new IllegalArgumentException("no hay localidades elegidas en " + area.name());
        }
        Set<Locality> chosen = new LinkedHashSet<>();
        for (Locality locality : area.localities()) {
            if (selection.contains(locality)) {
                chosen.add(locality);
            }
        }
        for (Locality locality : selection) {
            if (!chosen.contains(locality)) {
                throw new IllegalArgumentException(locality.name() + " no es de " + area.name());
            }
        }
        return chosen;
    }

    private static String names(Set<Locality> localities) {
        return String.join("-", localities.stream().map(Locality::name).toList());
    }

    public Operation operation() {
        return area.verified();
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

    private static int vertex(RoadGraph graph, long osmNode, String what) {
        return graph.vertexOf(osmNode).orElseThrow(() -> new IllegalStateException(
                "el grafo no tiene " + what + " (nodo " + osmNode + "); reconstrúyelo"));
    }
}
