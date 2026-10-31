package me.ymid.rutea.app.operations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import me.ymid.rutea.app.planning.AreaPlanner;
import me.ymid.rutea.app.planning.DemoPlan;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.local.LocalSearch;

/**
 * La operación en curso de una flota: el plan vigente, las recogidas extra por desborde y los camiones fuera de
 * servicio.
 *
 * <p>Un contenedor desbordado se recoge lo antes posible. Si su visita del plan llega antes que cualquier desvío,
 * no se cambia nada. Si no, el camión en servicio que pueda llegar primero, con cupo en el viaje y margen en el
 * turno, agrega una recogida extra; la visita del plan se mantiene, porque el contenedor se vuelve a llenar. Si
 * ninguno puede, sale un camión de reserva. Las visitas del plan nunca se mueven: así la replanificación de una
 * avería ({@link BreakdownReplanner}) sigue partiendo del plan original.
 */
public final class LiveOperation {

    public enum Pickup { PLANNED, IN_SERVICE, RESERVE }

    /**
     * Qué camión en servicio se desvía: el que llega primero, o el que menos kilómetros agrega aunque llegue más
     * tarde. Las dos se miden en {@link OverflowBenchmark}.
     */
    public enum Policy { EARLIEST, SHORTEST_DETOUR }

    /**
     * @param truck camión que lo recoge; sin sentido si sale una reserva
     * @param pickupSeconds hora (desde el inicio del turno) a la que el camión llega al contenedor
     * @param extraMeters metros que se agregan a la operación: el desvío, el turno de la reserva o 0 si lo recoge
     *     su visita del plan
     * @param latencyNanos tiempo real de decidir
     */
    public record Dispatch(int node, long atSeconds, Pickup pickup, int truck, long pickupSeconds, long extraMeters,
            long latencyNanos) {
    }

    public enum ClosurePolicy {
        REROUTE,
        /**
         * Además reordena las paradas que faltan de cada viaje con búsqueda local sobre la red nueva, solo en los camiones
         * que el cierre cambió: los demás conservan el orden que conocen sus conductores.
         */
        RESEQUENCE
    }

    /** @param dropped visitas pendientes a contenedores aislados, que salen del plan */
    public record Closure(int shape, long atSeconds, TravelMatrix.Repair repair, List<Integer> isolated,
            List<Integer> dropped, List<TruckChange> changes, long latencyNanos) {

        public Closure {
            isolated = List.copyOf(isolated);
            dropped = List.copyOf(dropped);
            changes = List.copyOf(changes);
        }
    }

    /** @param rerouted si iba en camino por la vía cuando la cerraron */
    public record TruckChange(int truck, long extraMeters, long extraSeconds, boolean rerouted, boolean late) {
    }

    private record Slot(int truck, int trip, int position, long pickupSeconds, long meters) {
    }

    private final LivePlan live;
    private final Policy policy;
    private ClosurePolicy closurePolicy = ClosurePolicy.RESEQUENCE;
    private final Set<Integer> outOfService = new HashSet<>();
    private int nextTag = 1;

    public LiveOperation(AreaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule,
            int serviceSeconds) {
        this(scenario, solution, schedule, serviceSeconds, Policy.EARLIEST);
    }

    public LiveOperation(AreaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule,
            int serviceSeconds, Policy policy) {
        live = LivePlan.of(scenario, solution, schedule, serviceSeconds);
        this.policy = policy;
    }

    public Dispatch overflow(int node, long atSeconds) {
        long start = System.nanoTime();
        if (node < 1 || node >= live.scenario().gate()) {
            throw new IllegalArgumentException("no hay contenedor en el nodo " + node);
        }
        if (isolated(node, live.travelAt(atSeconds))) {
            throw new IllegalArgumentException("el contenedor del nodo " + node + " quedó aislado por un cierre de vía");
        }
        Slot planned = plannedVisit(node, atSeconds);
        Slot extra = bestExtra(node, atSeconds);
        if (planned != null && (extra == null || planned.pickupSeconds() <= extra.pickupSeconds())) {
            return new Dispatch(node, atSeconds, Pickup.PLANNED, planned.truck(), planned.pickupSeconds(), 0,
                    System.nanoTime() - start);
        }
        if (extra != null) {
            live.insert(extra.truck(), extra.trip(), extra.position(), node, nextTag++);
            return new Dispatch(node, atSeconds, Pickup.IN_SERVICE, extra.truck(), extra.pickupSeconds(),
                    extra.meters(), System.nanoTime() - start);
        }
        int gate = live.scenario().gate();
        TravelMatrix travel = live.travelAt(atSeconds + BogotaFigures.RESERVE_DISPATCH_SECONDS);
        long pickup = atSeconds + BogotaFigures.RESERVE_DISPATCH_SECONDS + travel.seconds(0, node);
        long meters = travel.meters(0, node) + travel.meters(node, gate) + travel.meters(gate, 0);
        return new Dispatch(node, atSeconds, Pickup.RESERVE, -1, pickup, meters, System.nanoTime() - start);
    }

    /**
     * Saca de servicio un camión (una avería) y vuelve a despachar las recogidas extra que no alcanzó a hacer. Sus
     * visitas del plan las cubre {@link BreakdownReplanner}.
     */
    public List<Dispatch> outOfService(int truck, long atSeconds) {
        outOfService.add(truck);
        List<Integer> pending = new ArrayList<>();
        long[][] leave = live.departures(truck);
        for (int i = live.tripCount(truck) - 1; i >= 0; i--) {
            for (int p = live.trip(truck, i).length - 1; p >= 0; p--) {
                if (live.tag(truck, i, p) != 0 && leave[i][p + 1] > atSeconds) {
                    pending.add(0, live.trip(truck, i)[p]);
                    live.remove(truck, i, p);
                }
            }
        }
        List<Dispatch> again = new ArrayList<>(pending.size());
        for (int node : pending) {
            again.add(overflow(node, atSeconds));
        }
        return again;
    }

    public void closurePolicy(ClosurePolicy policy) {
        closurePolicy = policy;
    }

    /**
     * Las visitas pendientes a contenedores que quedan aislados salen del plan. Los camiones averiados no se
     * replanifican: su reserva ya salió.
     *
     * @throws IllegalArgumentException si el cierre aísla la base o la portería, el trazo ya está cerrado o es
     *     anterior al último cierre
     */
    public Closure close(int shape, long atSeconds) {
        long start = System.nanoTime();
        List<LivePlan.Network> networks = live.networks();
        LivePlan.Network last = networks.getLast();
        if (last.closed()[shape]) {
            throw new IllegalArgumentException("el trazo " + shape + " ya está cerrado");
        }
        TravelMatrix.Repair repair = last.travel().closing(new int[] {shape},
                Runtime.getRuntime().availableProcessors());
        TravelMatrix travel = repair.matrix();
        int gate = live.scenario().gate();
        if (!travel.reachable(0, gate) || !travel.reachable(gate, 0)) {
            throw new IllegalArgumentException("cerrar el trazo " + shape + " aísla la base o la portería");
        }
        long[] metersBefore = new long[live.truckCount()];
        long[] endBefore = new long[live.truckCount()];
        for (int t = 0; t < live.truckCount(); t++) {
            metersBefore[t] = live.meters(t);
            endBefore[t] = live.endSeconds(t);
        }
        live.addNetwork(atSeconds, travel, new int[] {shape});

        List<Integer> isolated = new ArrayList<>();
        for (int node = 1; node < gate; node++) {
            if (isolated(node, travel)) {
                isolated.add(node);
            }
        }
        List<Integer> dropped = new ArrayList<>();
        for (int t = 0; t < live.truckCount(); t++) {
            if (outOfService.contains(t)) {
                continue;
            }
            if (!isolated.isEmpty()) {
                long[][] leave = live.departures(t);
                for (int i = live.tripCount(t) - 1; i >= 0; i--) {
                    for (int p = live.trip(t, i).length - 1; p >= 0; p--) {
                        int node = live.trip(t, i)[p];
                        if (leave[i][p] >= atSeconds && isolated(node, travel)) {
                            dropped.add(node);
                            live.remove(t, i, p);
                        }
                    }
                }
            }
            boolean changed = live.meters(t) != metersBefore[t] || live.endSeconds(t) != endBefore[t];
            if (closurePolicy == ClosurePolicy.RESEQUENCE && changed) {
                resequence(t, atSeconds, travel);
            }
        }

        List<TruckChange> changes = new ArrayList<>();
        for (int t = 0; t < live.truckCount(); t++) {
            long meters = live.meters(t);
            long end = live.endSeconds(t);
            if (outOfService.contains(t) || meters == metersBefore[t] && end == endBefore[t]) {
                continue;
            }
            boolean rerouted = live.legs(t).stream().anyMatch(LivePlan.Leg::rerouted);
            changes.add(new TruckChange(t, meters - metersBefore[t], end - endBefore[t], rerouted,
                    end > live.shiftSeconds()));
        }
        return new Closure(shape, atSeconds, repair, isolated, dropped, changes, System.nanoTime() - start);
    }

    /** Visitas del plan que el camión no alcanzó a terminar a {@code atSeconds}: lo que queda huérfano si se avería. */
    public int[] pendingPlanned(int truck, long atSeconds) {
        return live.timeline(truck).stream()
                .filter(stop -> live.tag(truck, stop.trip(), stop.position()) == 0
                        && stop.arriveSeconds() + live.serviceSeconds() > atSeconds)
                .mapToInt(LivePlan.Stop::node).toArray();
    }

    public AreaScenario scenarioAt(long atSeconds) {
        TravelMatrix travel = live.travelAt(atSeconds);
        return travel == live.scenario().travel() ? live.scenario() : live.scenario().withTravel(travel);
    }

    public boolean closed(int shape) {
        return live.networks().getLast().closed()[shape];
    }

    public DemoPlan.Route route(int truck, String id) {
        List<List<AreaPlanner.Arc>> legs = new ArrayList<>();
        for (LivePlan.Leg leg : live.legs(truck)) {
            legs.add(live.arcs(leg));
        }
        return AreaPlanner.redraw(live.scenario(), trips(truck), legs, live.unloadSeconds(), id);
    }

    public AreaScenario scenario() {
        return live.scenario();
    }

    public int unloadSeconds() {
        return live.unloadSeconds();
    }

    public int shiftSeconds() {
        return live.shiftSeconds();
    }

    public List<int[]> trips(int truck) {
        List<int[]> trips = new ArrayList<>(live.tripCount(truck));
        for (int i = 0; i < live.tripCount(truck); i++) {
            trips.add(live.trip(truck, i).clone());
        }
        return trips;
    }

    public long meters(int truck) {
        return live.meters(truck);
    }

    public long endSeconds(int truck) {
        return live.endSeconds(truck);
    }

    public record Visit(int node, long arriveSeconds) {
    }

    public List<Visit> visits(int truck) {
        return live.timeline(truck).stream().map(stop -> new Visit(stop.node(), stop.arriveSeconds())).toList();
    }

    public int truckCount() {
        return live.truckCount();
    }

    public boolean inService(int truck) {
        return !outOfService.contains(truck);
    }

    /** Nodo → hora de llegada de cada visita del plan del camión, que se corre con las recogidas extra. */
    public Map<Integer, Long> plannedArrivals(int truck) {
        Map<Integer, Long> arrivals = new LinkedHashMap<>();
        for (LivePlan.Stop stop : live.timeline(truck)) {
            if (live.tag(truck, stop.trip(), stop.position()) == 0) {
                arrivals.put(stop.node(), stop.arriveSeconds());
            }
        }
        return arrivals;
    }

    private Slot plannedVisit(int node, long atSeconds) {
        for (int t = 0; t < live.truckCount(); t++) {
            if (outOfService.contains(t)) {
                continue;
            }
            for (LivePlan.Stop stop : live.timeline(t)) {
                if (stop.node() == node && live.tag(t, stop.trip(), stop.position()) == 0
                        && stop.arriveSeconds() + live.serviceSeconds() > atSeconds) {
                    return new Slot(t, stop.trip(), stop.position(), Math.max(stop.arriveSeconds(), atSeconds), 0);
                }
            }
        }
        return null;
    }

    /**
     * El mejor lugar para una recogida extra según la {@link Policy}; en empate, decide el otro criterio. Solo
     * después de un lugar del que el camión todavía no sale: ahí puede cambiar de rumbo sin dar la vuelta.
     */
    private Slot bestExtra(int node, long atSeconds) {
        int gate = live.scenario().gate();
        int demand = live.scenario().instance().demand(node);
        int capacity = live.scenario().instance().capacity();
        Slot best = null;
        for (int t = 0; t < live.truckCount(); t++) {
            if (outOfService.contains(t)) {
                continue;
            }
            long slack = live.shiftSeconds() - live.endSeconds(t);
            if (slack <= 0) {
                continue;
            }
            long[][] leave = live.departures(t);
            for (int i = 0; i < live.tripCount(t); i++) {
                if (capacity - live.tripLoad(t, i) < demand) {
                    continue;
                }
                int[] trip = live.trip(t, i);
                for (int p = 0; p <= trip.length; p++) {
                    if (leave[i][p] < atSeconds) {
                        continue;
                    }
                    TravelMatrix travel = live.travelAt(leave[i][p]);
                    int previous = p == 0 ? live.tripStart(i) : trip[p - 1];
                    int next = p == trip.length ? gate : trip[p];
                    long seconds = travel.seconds(previous, node) + live.serviceSeconds() + travel.seconds(node, next)
                            - travel.seconds(previous, next);
                    if (seconds > slack) {
                        continue;
                    }
                    long pickup = leave[i][p] + travel.seconds(previous, node);
                    long meters = travel.meters(previous, node) + travel.meters(node, next)
                            - travel.meters(previous, next);
                    Slot slot = new Slot(t, i, p, pickup, meters);
                    if (best == null || better(slot, best)) {
                        best = slot;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Reordena las paradas de cada viaje que el camión todavía no empieza a manejar. La primera se ancla en el lugar
     * al que ya va o donde está; el viaje sigue terminando en la portería.
     */
    private void resequence(int truck, long atSeconds, TravelMatrix travel) {
        int gate = live.scenario().gate();
        long[][] leave = live.departures(truck);
        for (int i = 0; i < live.tripCount(truck); i++) {
            int[] trip = live.trip(truck, i);
            int first = 0;
            while (first < trip.length && leave[i][first] < atSeconds) {
                first++;
            }
            int free = trip.length - first;
            if (free < 3) {
                continue;
            }
            int anchor = first == 0 ? live.tripStart(i) : trip[first - 1];
            int[] places = new int[free + 1];
            places[0] = anchor;
            System.arraycopy(trip, first, places, 1, free);
            int[][] distances = new int[free + 1][free + 1];
            int[] demands = new int[free + 1];
            for (int a = 0; a <= free; a++) {
                demands[a] = a == 0 ? 0 : live.scenario().instance().demand(places[a]);
                for (int b = 0; b <= free; b++) {
                    distances[a][b] = a == b ? 0 : travel.meters(places[a], b == 0 ? gate : places[b]);
                }
            }
            Instance part = new Instance("viaje", Arrays.stream(demands).sum(), demands,
                    DistanceMatrix.of(distances));
            int[] order = new int[free];
            Arrays.setAll(order, k -> k + 1);
            Solution improved = new LocalSearch(part).improve(Solution.of(order), new Random(truck * 31L + i),
                    Long.MAX_VALUE);
            if (improved.routeCount() != 1) {
                continue;
            }
            int[] positions = new int[free];
            for (int k = 0; k < free; k++) {
                positions[k] = first + improved.customer(0, k) - 1;
            }
            live.reorder(truck, i, first, positions);
        }
    }

    private static boolean isolated(int node, TravelMatrix travel) {
        return !travel.reachable(0, node) || !travel.reachable(node, 0);
    }

    private boolean better(Slot a, Slot b) {
        return switch (policy) {
            case EARLIEST -> a.pickupSeconds() < b.pickupSeconds()
                    || a.pickupSeconds() == b.pickupSeconds() && a.meters() < b.meters();
            case SHORTEST_DETOUR -> a.meters() < b.meters()
                    || a.meters() == b.meters() && a.pickupSeconds() < b.pickupSeconds();
        };
    }
}
