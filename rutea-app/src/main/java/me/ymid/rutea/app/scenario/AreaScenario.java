package me.ymid.rutea.app.scenario;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.IntPredicate;

import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.ServiceAreas.Operation;
import me.ymid.rutea.app.scenario.ServiceAreas.ServiceArea;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.geo.geometry.Haversine;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Instance;

/**
 * La operación de una ASE traducida a CVRP. Cada ruta es el turno de un compactador: sale de la base del
 * operador, recoge hasta llenarse y termina en la portería de Doña Juana a descargar. El nodo {@code i} es el contenedor
 * {@code containers.get(i - 1)}; el nodo 0 es dos lugares a la vez, y la matriz asimétrica lo permite: su fila
 * sale de la base y su columna llega a la portería. El regreso de la portería a la base es igual para todos los
 * camiones, así que no cambia la optimización: va en {@code returnMeters}.
 *
 * @param area la ASE
 * @param operation turno, frecuencia y compactador con que se planifica: la de su operador, o la de uno de sus turnos de
 *     cargue lateral ({@link SideLoading})
 * @param points vértice del grafo de cada nodo de la instancia; el del nodo 0 es la base
 * @param travel metros y segundos entre todos los lugares: 0 es la base, {@code i} el nodo {@code i} y
 *     {@link #gate()} la portería
 * @param sites sitios sensibles para R02 y R07; vacío si no se cargaron
 */
public record AreaScenario(ServiceArea area, Operation operation, RoadGraph graph, int gateVertex, int depotVertex,
        List<Container> containers, List<StreetChunks.Chunk> chunks, int[] points, Instance instance,
        TravelMatrix travel, List<SensitiveSites.Site> sites) {

    public AreaScenario {
        Objects.requireNonNull(operation, "operation");
        containers = List.copyOf(containers);
        chunks = List.copyOf(chunks);
        sites = List.copyOf(sites);
    }

    public AreaScenario withSites(List<SensitiveSites.Site> sensitive) {
        return new AreaScenario(area, operation, graph, gateVertex, depotVertex, containers, chunks, points, instance,
                travel, sensitive);
    }

    /** Un contenedor aislado por un cierre queda a {@link TravelMatrix#UNREACHABLE}. */
    public AreaScenario withTravel(TravelMatrix matrix) {
        int n = instance.size();
        int[] demands = new int[n];
        int[] collecting = new int[n];
        for (int i = 1; i < n; i++) {
            demands[i] = instance.demand(i);
            collecting[i] = collectingMeters(i);
        }
        Instance closed = instance(matrix, demands, collecting, instance.capacity(), instance.name());
        return new AreaScenario(area, operation, matrix.graph(), gateVertex, depotVertex, containers, chunks, points,
                closed, matrix, sites);
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
        // El sufijo -n<nodos> es el mismo de las instancias X: el arnés ordena por él.
        String name = prefix(area, chosen) + kind + "-n" + (containers.size() + 1) + "-s" + seed;
        return assemble(graph, area, area.verified(), containers, depot, gate, capacityKg, name);
    }

    /**
     * Los contenedores reales que el operador asigna a uno de sus turnos de cargue lateral, con la carga por capacidad
     * ({@link SideLoading#kgAtCollection}). Cada contenedor se visita en el extremo más cercano del trazo de calle más
     * cercano, y lleva el código del operador.
     */
    public static AreaScenario buildReal(RoadGraph graph, ServiceArea area, SideLoading.Shift shift,
            List<OperatorContainers.Container> real, double fill) {
        return buildReal(graph, area, shift, area.localities(), real, fill);
    }

    public static AreaScenario buildReal(RoadGraph graph, ServiceArea area, SideLoading.Shift shift,
            Collection<Locality> selection, List<OperatorContainers.Container> real, double fill) {
        return buildReal(graph, area, shift, selection, Localities.bogota(), real, fill, area.base().osmNode(),
                ServiceAreas.DISPOSAL_OSM_NODE);
    }

    static AreaScenario buildReal(RoadGraph graph, ServiceArea area, SideLoading.Shift shift,
            Collection<Locality> selection, Localities localities, List<OperatorContainers.Container> real,
            double fill, long depotNode, long gateNode) {
        Operation operation = SideLoading.of(area).operation(shift);
        Set<Locality> chosen = chosen(area, selection);
        List<Container> containers = realContainers(graph, area, shift, chosen, localities, real, fill);
        if (containers.isEmpty()) {
            throw new IllegalArgumentException("ningún contenedor de " + label(area, chosen) + " es del turno "
                    + shift.operatorName());
        }
        String name = String.format(Locale.ROOT, "%s%s-n%d-f%d", prefix(area, chosen), shift.name(),
                containers.size() + 1, Math.round(fill * 100));
        return assemble(graph, area, operation, containers, vertex(graph, depotNode, "la base"),
                vertex(graph, gateNode, "la portería del relleno"), operation.compactorKg(), name);
    }

    public static List<Container> realContainers(RoadGraph graph, ServiceArea area, SideLoading.Shift shift,
            Collection<Locality> selection, List<OperatorContainers.Container> real, double fill) {
        return realContainers(graph, area, shift, chosen(area, selection), Localities.bogota(), real, fill);
    }

    private static List<Container> realContainers(RoadGraph graph, ServiceArea area, SideLoading.Shift shift,
            Set<Locality> chosen, Localities localities, List<OperatorContainers.Container> real, double fill) {
        List<OperatorContainers.Container> selected = selected(area, shift, chosen, localities, real);
        if (selected.isEmpty()) {
            return List.of();
        }
        int[][] ends = shapeEnds(graph);
        List<Container> containers = new ArrayList<>(selected.size());
        for (OperatorContainers.Container c : selected) {
            int shape = graph.nearestShape(c.latE7(), c.lonE7());
            int start = ends[shape][0];
            int end = ends[shape][1];
            int vertex = Haversine.meters(c.latE7(), c.lonE7(), graph.latE7(start), graph.lonE7(start))
                    <= Haversine.meters(c.latE7(), c.lonE7(), graph.latE7(end), graph.lonE7(end)) ? start : end;
            containers.add(new Container(c.code(), WasteStream.NON_RECYCLABLE, c.latE7(), c.lonE7(), vertex, shape,
                    SideLoading.kgAtCollection(c.liters(), fill)));
        }
        return containers;
    }

    public static List<OperatorContainers.Container> selected(ServiceArea area, SideLoading.Shift shift,
            Collection<Locality> selection, List<OperatorContainers.Container> real) {
        return selected(area, shift, chosen(area, selection), Localities.bogota(), real);
    }

    private static List<OperatorContainers.Container> selected(ServiceArea area, SideLoading.Shift shift,
            Set<Locality> chosen, Localities localities, List<OperatorContainers.Container> real) {
        boolean whole = chosen.size() == area.localities().size();
        List<OperatorContainers.Container> selected = new ArrayList<>();
        for (OperatorContainers.Container c : real) {
            if (!c.shift().equals(shift.operatorName())) {
                continue;
            }
            // Con la ASE completa no se ubica: la capa del operador ya es la ASE.
            if (whole) {
                selected.add(c);
                continue;
            }
            Locality locality = localities.locate(c.latE7(), c.lonE7());
            if (!area.localities().contains(locality)) {
                throw new IllegalStateException("el contenedor " + c.code() + " de " + area.name() + " cae en "
                        + locality.name() + ", fuera de su ASE");
            }
            if (chosen.contains(locality)) {
                selected.add(c);
            }
        }
        return selected;
    }

    /**
     * La cantidad oficial de contenedores de una ASE que no publica su ubicación, como el ASE 5 (ADR 0013). Se ubican
     * con el método de F3 sobre las calles de la ASE, cada uno con un tamaño al azar entre {@code liters}, y cargan
     * por capacidad ({@link SideLoading#kgAtCollection}).
     */
    public static AreaScenario buildPlanned(RoadGraph graph, ServiceArea area, SideLoading.Shift shift, int count,
            List<Integer> liters, double fill, long seed) {
        return buildPlanned(graph, area, shift, area.localities(), count, liters, fill, seed);
    }

    /**
     * Solo los de las localidades elegidas. Como en {@link #build}, se ubican y se les sortea el tamaño sobre la ASE
     * completa, y la selección solo filtra.
     */
    public static AreaScenario buildPlanned(RoadGraph graph, ServiceArea area, SideLoading.Shift shift,
            Collection<Locality> selection, int count, List<Integer> liters, double fill, long seed) {
        return buildPlanned(graph, area, shift, selection, count, liters, fill, seed, Localities.bogota(),
                area.base().osmNode(), ServiceAreas.DISPOSAL_OSM_NODE);
    }

    static AreaScenario buildPlanned(RoadGraph graph, ServiceArea area, SideLoading.Shift shift,
            Collection<Locality> selection, int count, List<Integer> liters, double fill, long seed,
            Localities localities, long depotNode, long gateNode) {
        Operation operation = SideLoading.of(area).operation(shift);
        Set<Locality> chosen = chosen(area, selection);
        List<Container> containers = plannedContainers(graph, area, chosen, localities, count, liters, fill, seed);
        if (containers.isEmpty()) {
            throw new IllegalArgumentException("ningún contenedor simulado cae en " + label(area, chosen));
        }
        String name = String.format(Locale.ROOT, "%s%s-n%d-s%d-f%d", prefix(area, chosen), shift.name(),
                containers.size() + 1, seed, Math.round(fill * 100));
        return assemble(graph, area, operation, containers, vertex(graph, depotNode, "la base"),
                vertex(graph, gateNode, "la portería del relleno"), operation.compactorKg(), name);
    }

    public static List<Container> plannedContainers(RoadGraph graph, ServiceArea area, Collection<Locality> selection,
            int count, List<Integer> liters, double fill, long seed) {
        return plannedContainers(graph, area, chosen(area, selection), Localities.bogota(), count, liters, fill, seed);
    }

    private static List<Container> plannedContainers(RoadGraph graph, ServiceArea area, Set<Locality> chosen,
            Localities localities, int count, List<Integer> liters, double fill, long seed) {
        // La carga media no importa: cada carga se reemplaza por la de la capacidad.
        List<Container> placed = SimulatedContainers.place(graph, streets(graph, area, localities),
                WasteStream.NON_RECYCLABLE, count, 1, seed);
        SplittableRandom sizes = new SplittableRandom(seed * 31 + 29);
        List<Container> containers = new ArrayList<>(placed.size());
        for (Container c : placed) {
            int size = liters.get(sizes.nextInt(liters.size()));
            if (chosen.contains(localities.ofShape(graph, c.shape()))) {
                containers.add(new Container(c.id(), c.stream(), c.latE7(), c.lonE7(), c.vertex(), c.shape(),
                        SideLoading.kgAtCollection(size, fill)));
            }
        }
        return containers;
    }

    /** Los extremos de cada trazo en su sentido de dibujo: un trazo de doble sentido aparece en dos arcos. */
    private static int[][] shapeEnds(RoadGraph graph) {
        int[][] ends = new int[graph.shapeCount()][];
        for (int e = 0; e < graph.edgeCount(); e++) {
            int shape = graph.shape(e);
            int base = shape >= 0 ? shape : ~shape;
            if (ends[base] == null) {
                ends[base] = shape >= 0 ? new int[] {graph.source(e), graph.target(e)}
                        : new int[] {graph.target(e), graph.source(e)};
            }
        }
        return ends;
    }

    /**
     * El puerta a puerta de una franja: los contenedores primero y después los trozos de calle (ADR 0014), que se
     * entran por un extremo, se recogen enteros y se dejan por el otro.
     */
    public static AreaScenario buildDoorToDoor(RoadGraph graph, ServiceArea area, Operation operation,
            List<Container> containers, List<StreetChunks.Chunk> chunks, double kgPerMeter, String name) {
        return buildDoorToDoor(graph, area, operation, containers, chunks, vertex(graph, area.base().osmNode(),
                "la base"), vertex(graph, ServiceAreas.DISPOSAL_OSM_NODE, "la portería del relleno"), kgPerMeter, name);
    }

    static AreaScenario buildDoorToDoor(RoadGraph graph, ServiceArea area, Operation operation,
            List<Container> containers, List<StreetChunks.Chunk> chunks, int depot, int gate, double kgPerMeter,
            String name) {
        return assemble(graph, area, operation, containers, chunks, depot, gate, kgPerMeter, operation.compactorKg(),
                name);
    }

    private static AreaScenario assemble(RoadGraph graph, ServiceArea area, Operation operation,
            List<Container> containers, int depot, int gate, int capacityKg, String name) {
        return assemble(graph, area, operation, containers, List.of(), depot, gate, 0, capacityKg, name);
    }

    private static AreaScenario assemble(RoadGraph graph, ServiceArea area, Operation operation,
            List<Container> containers, List<StreetChunks.Chunk> chunks, int depot, int gate, double kgPerMeter,
            int capacityKg, String name) {
        int[] demands = demands(containers, chunks, kgPerMeter, capacityKg);
        TravelMatrix matrix = travel(graph, containers, chunks, depot, gate);
        Instance instance = instance(matrix, demands, collecting(containers, chunks), capacityKg, name);
        int[] points = new int[demands.length];
        for (int i = 0; i < points.length; i++) {
            points[i] = matrix.target(i);
        }
        return new AreaScenario(area, operation, graph, gate, depot, containers, chunks, points, instance, matrix,
                List.of());
    }

    /** [base, nodos..., portería]: la fila de un trozo sale de su salida y la columna llega a su entrada. */
    static TravelMatrix travel(RoadGraph graph, List<Container> containers, List<StreetChunks.Chunk> chunks,
            int depot, int gate) {
        int n = containers.size() + chunks.size() + 1;
        int[] exits = new int[n + 1];
        int[] entries = new int[n + 1];
        exits[0] = depot;
        entries[0] = depot;
        for (int i = 1; i <= containers.size(); i++) {
            exits[i] = containers.get(i - 1).vertex();
            entries[i] = exits[i];
        }
        for (int i = containers.size() + 1; i < n; i++) {
            StreetChunks.Chunk chunk = chunks.get(i - containers.size() - 1);
            exits[i] = chunk.exit();
            entries[i] = chunk.entry();
        }
        exits[n] = gate;
        entries[n] = gate;
        return TravelMatrix.compute(graph, exits, entries, Runtime.getRuntime().availableProcessors());
    }

    static int[] demands(List<Container> containers, List<StreetChunks.Chunk> chunks, double kgPerMeter,
            int capacityKg) {
        int[] demands = new int[containers.size() + chunks.size() + 1];
        for (int i = 1; i < demands.length; i++) {
            demands[i] = i <= containers.size() ? containers.get(i - 1).kg()
                    : Math.max(1, (int) Math.round(chunks.get(i - containers.size() - 1).meters() * kgPerMeter));
            if (demands[i] > capacityKg) {
                throw new IllegalStateException("el nodo " + i + " carga " + demands[i] + " kg y no cabe en un camión");
            }
        }
        return demands;
    }

    static int[] collecting(List<Container> containers, List<StreetChunks.Chunk> chunks) {
        int[] collecting = new int[containers.size() + chunks.size() + 1];
        for (int i = 0; i < chunks.size(); i++) {
            collecting[containers.size() + 1 + i] = chunks.get(i).meters();
        }
        return collecting;
    }

    /**
     * La matriz trae la portería en la última fila y columna: el nodo 0 de la instancia sale de la base y llega a la
     * portería. Entrar a un trozo cuesta además recorrerlo recogiendo.
     */
    static Instance instance(TravelMatrix matrix, int[] demands, int[] collecting, int capacityKg, String name) {
        int n = demands.length;
        int gate = matrix.size() - 1;
        int[][] distances = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                distances[i][j] = i == j ? 0 : matrix.meters(i, j == 0 ? gate : j) + collecting[j];
            }
        }
        return new Instance(name, capacityKg, demands, DistanceMatrix.of(distances));
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

    private static String prefix(ServiceArea area, Set<Locality> chosen) {
        return label(area, chosen).replace(" ", "");
    }

    private static String label(ServiceArea area, Set<Locality> chosen) {
        return chosen.size() == area.localities().size() ? area.name() : names(chosen);
    }

    private static String names(Set<Locality> localities) {
        return String.join("-", localities.stream().map(Locality::name).toList());
    }

    /** Un turno de puerta a puerta puede no tener contenedores: recoge no aprovechables. */
    public WasteStream stream() {
        return containers.isEmpty() ? WasteStream.NON_RECYCLABLE : containers.getFirst().stream();
    }

    /** Índice de la portería en {@link #travel}; la base es 0 y el nodo {@code i}, {@code i}. */
    public int gate() {
        return containers.size() + chunks.size() + 1;
    }

    /** Metros de la portería a la base: el regreso de cada camión al final del turno. */
    public int returnMeters() {
        return travel.meters(gate(), 0);
    }

    public int returnSeconds() {
        return travel.seconds(gate(), 0);
    }

    public int serviceSeconds(int node) {
        return isChunk(node) ? DoorToDoor.collectingSeconds(chunk(node).meters())
                : BogotaFigures.SERVICE_SECONDS_PER_CONTAINER;
    }

    public int collectingMeters(int node) {
        return isChunk(node) ? chunk(node).meters() : 0;
    }

    public String stopId(int node) {
        return isChunk(node) ? CHUNK_PREFIX + (node - containers.size()) : containers.get(node - 1).id();
    }

    /** Ningún código de contenedor, de operador o simulado, empieza así. */
    private static final String CHUNK_PREFIX = "T-";

    public static boolean isChunkStop(String id) {
        return id.startsWith(CHUNK_PREFIX);
    }

    public boolean isChunk(int node) {
        return node > containers.size() && node < gate();
    }

    public StreetChunks.Chunk chunk(int node) {
        return chunks.get(node - containers.size() - 1);
    }

    private static int vertex(RoadGraph graph, long osmNode, String what) {
        return graph.vertexOf(osmNode).orElseThrow(() -> new IllegalStateException(
                "el grafo no tiene " + what + " (nodo " + osmNode + "); reconstrúyelo"));
    }
}
