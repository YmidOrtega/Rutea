package me.ymid.rutea.app.operations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import me.ymid.rutea.app.planning.AreaPlanner;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.ShortestPaths;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.Solution;

/**
 * El plan en curso, que se puede modificar: por camión, sus viajes con las paradas en el orden en que las hace.
 * Los nodos son índices de {@link AreaScenario#travel}: 0 es la base y {@code gate()} la portería. Las horas se
 * calculan igual que {@link ShiftScheduler}: manejo por la matriz, paradas, descargas y regreso a la base.
 *
 * <p>Cada parada lleva una marca: 0 si es del plan, otra si se agregó después (un desborde). Un mismo contenedor
 * puede estar dos veces, en su visita del plan y en una recogida extra; la marca dice cuál es cuál.
 *
 * <p>Cada tramo usa la red vigente cuando el camión arranca. Si una vía se cierra mientras va en camino y el resto
 * del camino pasa por ella, termina el arco en que va y sigue por la red nueva. Un camión que quedó dentro de una
 * zona aislada sale por donde entró.
 */
final class LivePlan {

    record Stop(int truck, int trip, int position, int node, long arriveSeconds) {
    }

    /** @param position parada a la que llega, {@link #TO_GATE} o {@link #TO_BASE} */
    record Leg(int trip, int position, int from, int to, long departSeconds, long seconds, long meters, int network,
            boolean rerouted) {

        long arriveSeconds() {
            return departSeconds + seconds;
        }
    }

    static final int TO_GATE = -1;
    static final int TO_BASE = -2;

    record Network(long fromSeconds, TravelMatrix travel, boolean[] closed) {
    }

    private record Detour(long seconds, long meters, List<AreaPlanner.Arc> arcs, boolean rerouted) {
    }

    private record LegKey(int from, int to, long departSeconds) {
    }

    private final AreaScenario scenario;
    private final int serviceSeconds;
    private final int unloadSeconds;
    private final int shiftSeconds;
    private final List<List<int[]>> trucks = new ArrayList<>();
    private final List<List<int[]>> tags = new ArrayList<>();
    private final List<Network> networks = new ArrayList<>();
    private final Map<LegKey, Detour> detours = new HashMap<>();
    /** Despachar un desborde recorre los tramos de cada camión varias veces: se calculan una vez por cambio. */
    private final Map<Integer, List<Leg>> legs = new HashMap<>();
    private final Map<RoadGraph, ShortestPaths> searches = new IdentityHashMap<>();

    private LivePlan(AreaScenario scenario, int serviceSeconds, int unloadSeconds, int shiftSeconds) {
        this.scenario = scenario;
        this.serviceSeconds = serviceSeconds;
        this.unloadSeconds = unloadSeconds;
        this.shiftSeconds = shiftSeconds;
        networks.add(new Network(0, scenario.travel(), new boolean[scenario.graph().shapeCount()]));
    }

    static LivePlan of(AreaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule, int serviceSeconds) {
        LivePlan live = new LivePlan(scenario, serviceSeconds, schedule.unloadSeconds(), schedule.shiftSeconds());
        for (ShiftScheduler.Truck truck : schedule.trucks()) {
            List<int[]> trips = new ArrayList<>();
            List<int[]> marks = new ArrayList<>();
            for (int i = 0; i < truck.trips().length; i++) {
                int k = truck.trips()[i];
                int[] stops = new int[solution.routeLength(k)];
                for (int p = 0; p < stops.length; p++) {
                    stops[p] = solution.customer(k, truck.reversed()[i] ? stops.length - 1 - p : p);
                }
                trips.add(stops);
                marks.add(new int[stops.length]);
            }
            live.trucks.add(trips);
            live.tags.add(marks);
        }
        return live;
    }

    int truckCount() {
        return trucks.size();
    }

    int tripCount(int truck) {
        return trucks.get(truck).size();
    }

    int[] trip(int truck, int trip) {
        return trucks.get(truck).get(trip);
    }

    int tag(int truck, int trip, int position) {
        return tags.get(truck).get(trip)[position];
    }

    int unloadSeconds() {
        return unloadSeconds;
    }

    int shiftSeconds() {
        return shiftSeconds;
    }

    int serviceSeconds() {
        return serviceSeconds;
    }

    AreaScenario scenario() {
        return scenario;
    }

    /** Desde dónde sale el viaje: la base el primero, la portería los siguientes. */
    int tripStart(int trip) {
        return trip == 0 ? 0 : scenario.gate();
    }

    int tripLoad(int truck, int trip) {
        return Arrays.stream(trip(truck, trip)).map(scenario.instance()::demand).sum();
    }

    /** Los cierres van en orden: uno anterior al último cambiaría las redes que vinieron después. */
    void addNetwork(long fromSeconds, TravelMatrix travel, int[] closedShapes) {
        Network last = networks.getLast();
        if (fromSeconds < last.fromSeconds()) {
            throw new IllegalArgumentException("ya hay un cierre a " + last.fromSeconds() + " s; uno a " + fromSeconds
                    + " s tendría que cambiar lo que vino después");
        }
        boolean[] closed = last.closed().clone();
        for (int shape : closedShapes) {
            closed[shape] = true;
        }
        networks.add(new Network(fromSeconds, travel, closed));
        detours.clear();
        legs.clear();
    }

    List<Network> networks() {
        return List.copyOf(networks);
    }

    TravelMatrix travelAt(long seconds) {
        return networks.get(networkAt(seconds)).travel();
    }

    List<Leg> legs(int truck) {
        return legs.computeIfAbsent(truck, this::walk);
    }

    private List<Leg> walk(int truck) {
        List<Leg> legs = new ArrayList<>();
        long clock = 0;
        int gate = scenario.gate();
        for (int i = 0; i < tripCount(truck); i++) {
            int previous = tripStart(i);
            int[] trip = trip(truck, i);
            for (int p = 0; p < trip.length; p++) {
                Leg leg = leg(i, p, previous, trip[p], clock);
                legs.add(leg);
                clock = leg.arriveSeconds() + serviceSeconds;
                previous = trip[p];
            }
            Leg toGate = leg(i, TO_GATE, previous, gate, clock);
            legs.add(toGate);
            clock = toGate.arriveSeconds() + unloadSeconds;
        }
        legs.add(leg(tripCount(truck) - 1, TO_BASE, gate, 0, clock));
        return List.copyOf(legs);
    }

    List<Stop> timeline(int truck) {
        List<Stop> stops = new ArrayList<>();
        for (Leg leg : legs(truck)) {
            if (leg.position() >= 0) {
                stops.add(new Stop(truck, leg.trip(), leg.position(), leg.to(), leg.arriveSeconds()));
            }
        }
        return stops;
    }

    /**
     * Hora a la que el camión sale de cada lugar de un viaje: {@code [i][0]} del inicio del viaje (la base o la
     * portería, después de descargar) y {@code [i][p + 1]} de la parada {@code p}, ya atendida.
     */
    long[][] departures(int truck) {
        long[][] leave = new long[tripCount(truck)][];
        for (int i = 0; i < leave.length; i++) {
            leave[i] = new long[trip(truck, i).length + 1];
        }
        for (Leg leg : legs(truck)) {
            if (leg.position() >= 0) {
                leave[leg.trip()][leg.position()] = leg.departSeconds();
            } else if (leg.position() == TO_GATE) {
                leave[leg.trip()][trip(truck, leg.trip()).length] = leg.departSeconds();
            }
        }
        return leave;
    }

    long endSeconds(int truck) {
        return legs(truck).getLast().arriveSeconds();
    }

    long meters(int truck) {
        long meters = 0;
        for (Leg leg : legs(truck)) {
            meters += leg.meters();
        }
        return meters;
    }

    long meters() {
        long total = 0;
        for (int t = 0; t < truckCount(); t++) {
            total += meters(t);
        }
        return total;
    }

    List<AreaPlanner.Arc> arcs(Leg leg) {
        Detour detour = detour(leg.from(), leg.to(), leg.departSeconds(), leg.network());
        if (detour != null) {
            return detour.arcs();
        }
        return path(networks.get(leg.network()).travel().graph(), leg.from(), leg.to());
    }

    void insert(int truck, int trip, int position, int node) {
        insert(truck, trip, position, node, 0);
    }

    void insert(int truck, int trip, int position, int node, int tag) {
        legs.remove(truck);
        trucks.get(truck).set(trip, inserted(trip(truck, trip), position, node));
        tags.get(truck).set(trip, inserted(tags.get(truck).get(trip), position, tag));
    }

    void remove(int truck, int trip, int position) {
        legs.remove(truck);
        trucks.get(truck).set(trip, removed(trip(truck, trip), position));
        tags.get(truck).set(trip, removed(tags.get(truck).get(trip), position));
    }

    void reorder(int truck, int trip, int from, int[] positions) {
        int[] stops = trip(truck, trip).clone();
        int[] marks = tags.get(truck).get(trip).clone();
        int[] oldStops = stops.clone();
        int[] oldMarks = marks.clone();
        for (int k = 0; k < positions.length; k++) {
            stops[from + k] = oldStops[positions[k]];
            marks[from + k] = oldMarks[positions[k]];
        }
        legs.remove(truck);
        trucks.get(truck).set(trip, stops);
        tags.get(truck).set(trip, marks);
    }

    private Leg leg(int trip, int position, int from, int to, long depart) {
        int network = networkAt(depart);
        while (!networks.get(network).travel().reachable(from, to)) {
            network--;
        }
        Detour detour = detour(from, to, depart, network);
        if (detour != null) {
            return new Leg(trip, position, from, to, depart, detour.seconds(), detour.meters(), network,
                    detour.rerouted());
        }
        TravelMatrix travel = networks.get(network).travel();
        return new Leg(trip, position, from, to, depart, travel.seconds(from, to), travel.meters(from, to), network,
                false);
    }

    /** {@code null} si ninguna red empieza mientras el camión va en camino: entonces basta la matriz. */
    private Detour detour(int from, int to, long depart, int network) {
        int next = network + 1;
        while (next < networks.size() && networks.get(next).fromSeconds() <= depart) {
            next++;
        }
        TravelMatrix travel = networks.get(network).travel();
        if (next == networks.size() || networks.get(next).fromSeconds() >= depart + travel.seconds(from, to)) {
            return null;
        }
        LegKey key = new LegKey(from, to, depart);
        Detour cached = detours.get(key);
        if (cached != null) {
            return cached;
        }
        List<AreaPlanner.Arc> arcs = new ArrayList<>(path(travel.graph(), from, to));
        boolean rerouted = false;
        for (int j = next; j < networks.size(); j++) {
            Network closing = networks.get(j);
            long clock = depart;
            int at = 0;
            while (at < arcs.size() && clock < closing.fromSeconds()) {
                clock += arcs.get(at).seconds();
                at++;
            }
            if (at == arcs.size()) {
                break;
            }
            boolean crosses = false;
            for (int k = at; k < arcs.size() && !crosses; k++) {
                int shape = arcs.get(k).graph().shape(arcs.get(k).edge());
                crosses = closing.closed()[shape < 0 ? ~shape : shape];
            }
            if (!crosses) {
                continue;
            }
            RoadGraph graph = closing.travel().graph();
            int here = at == 0 ? travel.source(from) : arcs.get(at - 1).graph().target(arcs.get(at - 1).edge());
            int target = travel.target(to);
            ShortestPaths search = search(graph);
            search.distances(here, new int[] {target});
            if (search.distance(target) == ShortestPaths.UNREACHABLE) {
                // El destino quedó aislado con el camión ya en camino: llega.
                continue;
            }
            List<AreaPlanner.Arc> rest = new ArrayList<>(arcs.subList(0, at));
            for (int edge : search.pathEdges(target)) {
                rest.add(new AreaPlanner.Arc(graph, edge));
            }
            arcs = rest;
            rerouted = true;
        }
        long seconds = 0;
        long meters = 0;
        for (AreaPlanner.Arc arc : arcs) {
            seconds += arc.seconds();
            meters += arc.meters();
        }
        Detour detour = new Detour(seconds, meters, List.copyOf(arcs), rerouted);
        detours.put(key, detour);
        return detour;
    }

    private ShortestPaths search(RoadGraph graph) {
        return searches.computeIfAbsent(graph, g -> new ShortestPaths(g, ShortestPaths.Weight.METERS));
    }

    private List<AreaPlanner.Arc> path(RoadGraph graph, int from, int to) {
        ShortestPaths search = search(graph);
        int target = scenario.travel().target(to);
        search.distances(scenario.travel().source(from), new int[] {target});
        List<AreaPlanner.Arc> arcs = new ArrayList<>();
        for (int edge : search.pathEdges(target)) {
            arcs.add(new AreaPlanner.Arc(graph, edge));
        }
        return arcs;
    }

    private int networkAt(long seconds) {
        int network = 0;
        while (network + 1 < networks.size() && networks.get(network + 1).fromSeconds() <= seconds) {
            network++;
        }
        return network;
    }

    private static int[] inserted(int[] values, int position, int value) {
        int[] grown = new int[values.length + 1];
        System.arraycopy(values, 0, grown, 0, position);
        grown[position] = value;
        System.arraycopy(values, position, grown, position + 1, values.length - position);
        return grown;
    }

    private static int[] removed(int[] values, int position) {
        int[] shrunk = new int[values.length - 1];
        System.arraycopy(values, 0, shrunk, 0, position);
        System.arraycopy(values, position + 1, shrunk, position, shrunk.length - position);
        return shrunk;
    }
}
