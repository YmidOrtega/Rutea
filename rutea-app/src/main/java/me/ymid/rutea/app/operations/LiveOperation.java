package me.ymid.rutea.app.operations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.Solution;

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

    /** Quién recoge el contenedor desbordado. */
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

    private record Slot(int truck, int trip, int position, long pickupSeconds, long meters) {
    }

    private final LivePlan live;
    private final Policy policy;
    private final Set<Integer> outOfService = new HashSet<>();
    private int nextTag = 1;

    public LiveOperation(SubaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule,
            int serviceSeconds) {
        this(scenario, solution, schedule, serviceSeconds, Policy.EARLIEST);
    }

    public LiveOperation(SubaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule,
            int serviceSeconds, Policy policy) {
        live = LivePlan.of(scenario, solution, schedule, serviceSeconds);
        this.policy = policy;
    }

    public Dispatch overflow(int node, long atSeconds) {
        long start = System.nanoTime();
        if (node < 1 || node >= live.scenario().gate()) {
            throw new IllegalArgumentException("no hay contenedor en el nodo " + node);
        }
        TravelMatrix travel = live.scenario().travel();
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
        long pickup = atSeconds + SubaFigures.RESERVE_DISPATCH_SECONDS + travel.seconds(0, node);
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

    public SubaScenario scenario() {
        return live.scenario();
    }

    public int unloadSeconds() {
        return live.unloadSeconds();
    }

    public int shiftSeconds() {
        return live.shiftSeconds();
    }

    /** Los viajes del camión, con las recogidas extra en su lugar. */
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

    /** La visita del plan que todavía no termina, si su camión sigue en servicio. */
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
        TravelMatrix travel = live.scenario().travel();
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

    private boolean better(Slot a, Slot b) {
        return switch (policy) {
            case EARLIEST -> a.pickupSeconds() < b.pickupSeconds()
                    || a.pickupSeconds() == b.pickupSeconds() && a.meters() < b.meters();
            case SHORTEST_DETOUR -> a.meters() < b.meters()
                    || a.meters() == b.meters() && a.pickupSeconds() < b.pickupSeconds();
        };
    }
}
