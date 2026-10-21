package me.ymid.rutea.app.operations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.domain.CollectionRules;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * Replanifica cuando un camión se avería (ADR 0005, R05): se congela lo recorrido, los contenedores que el camión
 * no alcanzó a visitar quedan huérfanos y los cubren camiones de reserva que salen de la base. ALNS arranca desde
 * los huérfanos en el orden que tenían en el plan.
 */
public final class BreakdownReplanner {

    /**
     * El plan nuevo y lo que costó tenerlo.
     *
     * @param orphans nodos de la instancia que el camión averiado no alcanzó a terminar de recoger, en el orden que
     *     tenían
     * @param trips viajes nuevos sobre los nodos originales; sin rutas si no había huérfanos
     * @param reserve camiones de reserva con sus viajes; sin camiones si no había huérfanos
     * @param latencyNanos tiempo real de replanificar: de la avería al plan nuevo
     * @param firstPickupSeconds hora (desde el inicio del turno) en que la reserva llega al primer huérfano
     * @param lastPickupSeconds hora en que recoge el último
     */
    public record Replan(int truck, long breakdownSeconds, int[] orphans, Solution trips,
            ShiftScheduler.Schedule reserve, long latencyNanos, long firstPickupSeconds, long lastPickupSeconds) {

        /** R05: el servicio se restablece en máximo tres horas, contado hasta que la reserva llega. */
        public boolean meetsRecoveryRule() {
            return orphans.length == 0 || Duration.ofSeconds(firstPickupSeconds - breakdownSeconds)
                    .compareTo(CollectionRules.MAX_BREAKDOWN_RECOVERY) <= 0;
        }

        public long reserveMeters() {
            return reserve.meters();
        }
    }

    private BreakdownReplanner() {
    }

    public static Replan afterBreakdown(SubaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule,
            int truck, long breakdownSeconds, long iterations, long seed) {
        long start = System.nanoTime();
        ShiftScheduler.Truck broken = schedule.trucks().get(truck);
        List<Integer> pending = new ArrayList<>();
        for (ShiftScheduler.Visit visit : ShiftScheduler.timeline(scenario, solution, broken,
                SubaFigures.SERVICE_SECONDS_PER_CONTAINER, schedule.unloadSeconds(), 0)) {
            // Huérfano si el camión no alcanzó a terminar de recogerlo: averiado a mitad del servicio, lo recoge la
            // reserva.
            if (visit.arriveSeconds() + SubaFigures.SERVICE_SECONDS_PER_CONTAINER > breakdownSeconds) {
                pending.add(visit.node());
            }
        }
        int[] orphans = pending.stream().mapToInt(Integer::intValue).toArray();
        if (orphans.length == 0) {
            ShiftScheduler.Schedule none = new ShiftScheduler.Schedule(List.of(), schedule.unloadSeconds(),
                    schedule.shiftSeconds());
            return new Replan(truck, breakdownSeconds, orphans, Solution.of(), none, System.nanoTime() - start,
                    breakdownSeconds, breakdownSeconds);
        }

        Instance whole = scenario.instance();
        Instance part = subInstance(whole, orphans);
        Solution warm = new AlnsSolver().solveFrom(part,
                new SolverSettings(seed, Duration.ofMinutes(10), iterations), inPlanOrder(part));
        Solution trips = toOriginalNodes(warm, orphans);
        ShiftScheduler.Schedule reserve = ShiftScheduler.schedule(scenario, trips,
                SubaFigures.SERVICE_SECONDS_PER_CONTAINER, schedule.unloadSeconds(), SubaFigures.SHIFT_SECONDS, 3);
        long latency = System.nanoTime() - start;

        long dispatch = breakdownSeconds + SubaFigures.RESERVE_DISPATCH_SECONDS;
        long first = Long.MAX_VALUE;
        long last = 0;
        for (ShiftScheduler.Truck reserveTruck : reserve.trucks()) {
            List<ShiftScheduler.Visit> visits = ShiftScheduler.timeline(scenario, trips, reserveTruck,
                    SubaFigures.SERVICE_SECONDS_PER_CONTAINER, schedule.unloadSeconds(), dispatch);
            first = Math.min(first, visits.getFirst().arriveSeconds());
            last = Math.max(last, visits.getLast().arriveSeconds());
        }
        return new Replan(truck, breakdownSeconds, orphans, trips, reserve, latency, first, last);
    }

    /** Los huérfanos con la misma matriz: el nodo 0 sale de la base y llega a la portería, como en el plan. */
    static Instance subInstance(Instance whole, int[] orphans) {
        int n = orphans.length + 1;
        int[] nodes = new int[n];
        System.arraycopy(orphans, 0, nodes, 1, orphans.length);
        int[][] distances = new int[n][n];
        int[] demands = new int[n];
        for (int i = 0; i < n; i++) {
            demands[i] = whole.demand(nodes[i]);
            for (int j = 0; j < n; j++) {
                distances[i][j] = whole.distance(nodes[i], nodes[j]);
            }
        }
        return new Instance(whole.name() + "-huerfanos", whole.capacity(), demands, DistanceMatrix.of(distances));
    }

    /** El arranque: los huérfanos en el orden que tenían, cortados en viajes cuando el siguiente no cabe. */
    static Solution inPlanOrder(Instance part) {
        List<int[]> routes = new ArrayList<>();
        List<Integer> route = new ArrayList<>();
        int load = 0;
        for (int node = 1; node < part.size(); node++) {
            if (load + part.demand(node) > part.capacity() && !route.isEmpty()) {
                routes.add(route.stream().mapToInt(Integer::intValue).toArray());
                route = new ArrayList<>();
                load = 0;
            }
            route.add(node);
            load += part.demand(node);
        }
        routes.add(route.stream().mapToInt(Integer::intValue).toArray());
        return Solution.of(routes.toArray(int[][]::new));
    }

    static Solution toOriginalNodes(Solution part, int[] orphans) {
        int[][] routes = new int[part.routeCount()][];
        for (int k = 0; k < routes.length; k++) {
            routes[k] = new int[part.routeLength(k)];
            for (int p = 0; p < routes[k].length; p++) {
                routes[k][p] = orphans[part.customer(k, p) - 1];
            }
        }
        return Solution.of(routes);
    }
}
