package me.ymid.rutea.app.operations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.domain.CollectionRules;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * Replanifica cuando un camión se avería (ADR 0005, R05): se congela lo recorrido y los contenedores que el camión
 * no alcanzó a recoger quedan huérfanos. Según la {@link Strategy}, los absorben camiones en servicio con margen o
 * camiones de reserva que salen de la base; para la reserva, ALNS arranca desde los huérfanos en el orden que tenían.
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
     * @param firstPickupSeconds hora (desde el inicio del turno) en que se recoge el primer huérfano, por la reserva o
     *     por un camión en servicio
     * @param lastPickupSeconds hora en que recoge el último
     */
    public record Replan(int truck, long breakdownSeconds, int[] orphans, Solution trips,
            ShiftScheduler.Schedule reserve, long latencyNanos, long firstPickupSeconds, long lastPickupSeconds,
            List<Reassignment> reassigned) {

        public Replan {
            reassigned = List.copyOf(reassigned);
        }

        public long extraMeters() {
            return reserve.meters() + reassigned.stream().mapToLong(Reassignment::extraMeters).sum();
        }

        /** R05: el servicio se restablece en máximo tres horas, contado hasta que se recoge el primer huérfano. */
        public boolean meetsRecoveryRule() {
            return orphans.length == 0 || Duration.ofSeconds(firstPickupSeconds - breakdownSeconds)
                    .compareTo(CollectionRules.MAX_BREAKDOWN_RECOVERY) <= 0;
        }

        public long reserveMeters() {
            return reserve.meters();
        }
    }

    public record Reassignment(int node, int truck, long extraMeters, long pickupSeconds) {
    }

    private BreakdownReplanner() {
    }

    public enum Strategy {
        /** Solo camiones de reserva: ningún camión en servicio cambia de ruta (ADR 0005). */
        RESERVE_ONLY,
        /** Primero se insertan en camiones en servicio con margen; lo que no cabe va a la reserva. */
        REASSIGN_FIRST,
        /** Se reasigna solo si así no hace falta la reserva; si no alcanza, solo reserva. */
        REASSIGN_IF_NO_RESERVE
    }

    public static Replan afterBreakdown(AreaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule,
            int truck, long breakdownSeconds, long iterations, long seed) {
        return afterBreakdown(scenario, solution, schedule, truck, breakdownSeconds, iterations, seed,
                Strategy.RESERVE_ONLY);
    }

    public static Replan afterBreakdown(AreaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule,
            int truck, long breakdownSeconds, long iterations, long seed, Strategy strategy) {
        long start = System.nanoTime();
        ShiftScheduler.Truck broken = schedule.trucks().get(truck);
        List<Integer> pending = new ArrayList<>();
        for (ShiftScheduler.Visit visit : ShiftScheduler.timeline(scenario, solution, broken, schedule.unloadSeconds(), 0)) {
            // Huérfano si el camión no alcanzó a terminar de recogerlo: averiado a mitad del servicio, lo recoge la
            // reserva.
            if (visit.arriveSeconds() + scenario.serviceSeconds(visit.node()) > breakdownSeconds) {
                pending.add(visit.node());
            }
        }
        int[] orphans = pending.stream().mapToInt(Integer::intValue).toArray();
        List<Reassignment> reassigned = new ArrayList<>();
        if (strategy != Strategy.RESERVE_ONLY && orphans.length > 0) {
            List<Integer> left = new ArrayList<>(pending);
            LivePlan live = LivePlan.of(scenario, solution, schedule);
            List<Insertion.Placement> placed = Insertion.cheapest(live, left, breakdownSeconds, truck);
            if (strategy == Strategy.REASSIGN_FIRST || left.isEmpty()) {
                for (Insertion.Placement placement : placed) {
                    long pickup = live.timeline(placement.truck()).stream()
                            .filter(stop -> stop.node() == placement.node()).findFirst().orElseThrow().arriveSeconds();
                    reassigned.add(new Reassignment(placement.node(), placement.truck(), placement.extraMeters(),
                            pickup));
                }
                pending = left;
            }
        }
        return withReserve(scenario, truck, breakdownSeconds, orphans, pending.stream().mapToInt(Integer::intValue)
                .toArray(), reassigned, schedule.unloadSeconds(), iterations, seed, start);
    }

    /**
     * Solo reserva, con los huérfanos ya calculados sobre el plan en vivo y la red vigente: después de un desborde o
     * un cierre de vía, el plan original ya no dice qué le faltaba al camión.
     */
    public static Replan afterBreakdown(AreaScenario scenario, int truck, long breakdownSeconds, int[] orphans,
            int unloadSeconds, long iterations, long seed) {
        return withReserve(scenario, truck, breakdownSeconds, orphans, orphans, List.of(), unloadSeconds, iterations,
                seed, System.nanoTime());
    }

    private static Replan withReserve(AreaScenario scenario, int truck, long breakdownSeconds, int[] orphans,
            int[] forReserve, List<Reassignment> reassigned, int unloadSeconds, long iterations, long seed,
            long start) {
        long firstAbsorbed = reassigned.stream().mapToLong(Reassignment::pickupSeconds).min().orElse(Long.MAX_VALUE);
        long lastAbsorbed = reassigned.stream().mapToLong(Reassignment::pickupSeconds).max().orElse(breakdownSeconds);
        if (forReserve.length == 0) {
            ShiftScheduler.Schedule none = new ShiftScheduler.Schedule(List.of(), unloadSeconds,
                    scenario.operation().shiftSeconds());
            long first = orphans.length == 0 ? breakdownSeconds : firstAbsorbed;
            return new Replan(truck, breakdownSeconds, orphans, Solution.of(), none, System.nanoTime() - start, first,
                    lastAbsorbed, reassigned);
        }

        Instance whole = scenario.instance();
        Instance part = subInstance(whole, forReserve);
        Solution warm = new AlnsSolver().solveFrom(part,
                new SolverSettings(seed, Duration.ofMinutes(10), iterations), inPlanOrder(part));
        Solution trips = toOriginalNodes(warm, forReserve);
        ShiftScheduler.Schedule reserve = ShiftScheduler.schedule(scenario, trips, unloadSeconds, scenario.operation().shiftSeconds(), scenario.operation().maxTripsPerTruck());
        long latency = System.nanoTime() - start;

        long dispatch = breakdownSeconds + BogotaFigures.RESERVE_DISPATCH_SECONDS;
        long first = Long.MAX_VALUE;
        long last = 0;
        for (ShiftScheduler.Truck reserveTruck : reserve.trucks()) {
            List<ShiftScheduler.Visit> visits = ShiftScheduler.timeline(scenario, trips, reserveTruck, unloadSeconds, dispatch);
            first = Math.min(first, visits.getFirst().arriveSeconds());
            last = Math.max(last, visits.getLast().arriveSeconds());
        }
        return new Replan(truck, breakdownSeconds, orphans, trips, reserve, latency, Math.min(first, firstAbsorbed),
                Math.max(last, lastAbsorbed), reassigned);
    }

    /** Los huérfanos con la misma matriz: el nodo 0 sale de la base y llega a la portería, como en el plan. */
    public static Instance subInstance(Instance whole, int[] orphans) {
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

    public static Solution toOriginalNodes(Solution part, int[] orphans) {
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
