package me.ymid.rutea.app.planning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.Solution;

/**
 * Reparte los viajes de una solución entre camiones, para que un mismo compactador haga varios en su turno:
 * base → viaje → descarga en el relleno → viaje → descarga → base. El primer viaje de cada camión sale de la
 * base y los siguientes, de la portería; cada uno se recorre en el sentido que mide menos.
 *
 * <p>Hasta {@link #MAX_EXACT_TRIPS} viajes la asignación es exacta: programación dinámica sobre los subconjuntos de
 * viajes, que minimiza primero los camiones y después los km. Con más, los viajes se parten en grupos que se asignan
 * exactos por separado, y el reparto lleva una cota inferior de camiones para saber cuánto podría sobrar. Con
 * {@code maxTripsPerTruck = 1} da un camión por viaje.
 *
 * <p>Varios viajes solo se encadenan si caben en el turno. Un viaje que ni solo cabe no se descarta: queda en
 * su propio camión, marcado como horas extra, porque eso también es un dato del plan.
 */
public final class ShiftScheduler {

    /** 2^20 subconjuntos: más viajes que esto ya no se resuelven exactos en un tiempo razonable. */
    static final int MAX_EXACT_TRIPS = 20;

    /**
     * Un camión y sus viajes en orden. {@code reversed[i]}: si el viaje {@code i} se recorre al revés.
     *
     * @param meters con el regreso a la base
     * @param seconds manejo, paradas, descargas y regreso
     */
    public record Truck(int[] trips, boolean[] reversed, long meters, long seconds) {
    }

    /**
     * @param lowerBound ningún reparto de estos viajes usa menos camiones; es {@code trucks.size()} cuando la
     *     asignación es exacta
     */
    public record Schedule(List<Truck> trucks, int unloadSeconds, int shiftSeconds, int lowerBound) {

        public Schedule {
            trucks = List.copyOf(trucks);
        }

        public Schedule(List<Truck> trucks, int unloadSeconds, int shiftSeconds) {
            this(trucks, unloadSeconds, shiftSeconds, trucks.size());
        }

        /** Se sabe que no hay reparto con menos camiones. */
        public boolean fewestTrucks() {
            return trucks.size() == lowerBound;
        }

        public long meters() {
            return trucks.stream().mapToLong(Truck::meters).sum();
        }

        /** Camiones cuyo turno pasa de {@code shiftSeconds}: solo puede pasar con un viaje que ni solo cabe. */
        public long overtimeTrucks() {
            return trucks.stream().filter(truck -> truck.seconds() > shiftSeconds).count();
        }
    }

    private ShiftScheduler() {
    }

    public static Schedule schedule(AreaScenario scenario, Solution solution, int serviceSeconds, int unloadSeconds,
            int shiftSeconds, int maxTripsPerTruck) {
        if (maxTripsPerTruck < 1 || maxTripsPerTruck > 3) {
            throw new IllegalArgumentException("de 1 a 3 viajes por camión, no " + maxTripsPerTruck);
        }
        Trips trips = new Trips(scenario, solution, serviceSeconds);
        int n = solution.routeCount();
        List<Truck> trucks = new ArrayList<>();
        if (n <= MAX_EXACT_TRIPS) {
            int[] all = new int[n];
            Arrays.setAll(all, i -> i);
            trucks.addAll(exact(trips, all, unloadSeconds, shiftSeconds, maxTripsPerTruck));
        } else {
            for (int[] group : groups(trips, n, unloadSeconds)) {
                trucks.addAll(exact(trips, group, unloadSeconds, shiftSeconds, maxTripsPerTruck));
            }
        }
        // El orden de salida sigue el del primer viaje de cada camión: es determinista y fácil de leer.
        trucks.sort((x, y) -> Integer.compare(x.trips()[0], y.trips()[0]));
        int lowerBound = n <= MAX_EXACT_TRIPS ? trucks.size()
                : lowerBound(trips, n, unloadSeconds, shiftSeconds, maxTripsPerTruck);
        return new Schedule(trucks, unloadSeconds, shiftSeconds, lowerBound);
    }

    /**
     * Con más de {@link #MAX_EXACT_TRIPS} viajes, grupos de a lo sumo ese tamaño que se asignan exactos por separado.
     * Los viajes se reparten por turnos de mayor a menor duración, para que cada grupo tenga largos y cortos que
     * encadenar.
     */
    private static List<int[]> groups(Trips trips, int n, int unloadSeconds) {
        Integer[] order = new Integer[n];
        long[] alone = new long[n];
        for (int t = 0; t < n; t++) {
            order[t] = t;
            alone[t] = trips.truck(new int[] {t}, unloadSeconds).seconds();
        }
        Arrays.sort(order, (a, b) -> alone[a] != alone[b] ? Long.compare(alone[b], alone[a]) : Integer.compare(a, b));
        int count = (n + MAX_EXACT_TRIPS - 1) / MAX_EXACT_TRIPS;
        List<List<Integer>> members = new ArrayList<>();
        for (int g = 0; g < count; g++) {
            members.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            members.get(i % count).add(order[i]);
        }
        List<int[]> groups = new ArrayList<>();
        for (List<Integer> group : members) {
            groups.add(group.stream().mapToInt(Integer::intValue).sorted().toArray());
        }
        return groups;
    }

    /**
     * Ningún reparto usa menos camiones: cada viaje que ni solo cabe va en su propio camión, cada camión hace a lo
     * sumo {@code maxTripsPerTruck} viajes, y el turno de un camión dura al menos la suma de sus viajes, cada uno
     * desde la base o la portería (lo más cercano) hasta descargar.
     */
    private static int lowerBound(Trips trips, int n, int unloadSeconds, int shiftSeconds, int maxTripsPerTruck) {
        int overtime = 0;
        long seconds = 0;
        for (int t = 0; t < n; t++) {
            if (trips.truck(new int[] {t}, unloadSeconds).seconds() > shiftSeconds) {
                overtime++;
            } else {
                seconds += trips.shortestSeconds(t) + unloadSeconds;
            }
        }
        int byShift = overtime + (int) ((seconds + shiftSeconds - 1) / shiftSeconds);
        return Math.max(byShift, (n + maxTripsPerTruck - 1) / maxTripsPerTruck);
    }

    /** Programación dinámica sobre los subconjuntos de {@code ids}: primero menos camiones, después menos km. */
    private static List<Truck> exact(Trips trips, int[] ids, int unloadSeconds, int shiftSeconds,
            int maxTripsPerTruck) {
        int n = ids.length;
        // Todas las secuencias de viajes que caben en un turno, agrupadas por su viaje de menor posición en ids.
        List<List<Truck>> byLowest = new ArrayList<>();
        List<List<Integer>> bitsByLowest = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byLowest.add(new ArrayList<>());
            bitsByLowest.add(new ArrayList<>());
        }
        for (int a = 0; a < n; a++) {
            byLowest.get(a).add(trips.truck(new int[] {ids[a]}, unloadSeconds));
            bitsByLowest.get(a).add(1 << a);
            for (int b = 0; maxTripsPerTruck >= 2 && b < n; b++) {
                if (b == a) {
                    continue;
                }
                Truck pair = trips.truck(new int[] {ids[a], ids[b]}, unloadSeconds);
                if (pair.seconds() > shiftSeconds) {
                    continue;
                }
                byLowest.get(Math.min(a, b)).add(pair);
                bitsByLowest.get(Math.min(a, b)).add(1 << a | 1 << b);
                for (int c = 0; maxTripsPerTruck >= 3 && c < n; c++) {
                    if (c == a || c == b) {
                        continue;
                    }
                    Truck triple = trips.truck(new int[] {ids[a], ids[b], ids[c]}, unloadSeconds);
                    if (triple.seconds() <= shiftSeconds) {
                        int lowest = Math.min(a, Math.min(b, c));
                        byLowest.get(lowest).add(triple);
                        bitsByLowest.get(lowest).add(1 << a | 1 << b | 1 << c);
                    }
                }
            }
        }

        // costo[mask] = camiones · 2^40 + metros: comparar el long es comparar primero camiones y luego km.
        int full = (1 << n) - 1;
        long[] cost = new long[full + 1];
        Truck[] choice = new Truck[full + 1];
        int[] choiceBits = new int[full + 1];
        Arrays.fill(cost, Long.MAX_VALUE);
        cost[0] = 0;
        for (int mask = 1; mask <= full; mask++) {
            int lowest = Integer.numberOfTrailingZeros(mask);
            List<Truck> candidates = byLowest.get(lowest);
            List<Integer> candidateBits = bitsByLowest.get(lowest);
            for (int i = 0; i < candidates.size(); i++) {
                int bits = candidateBits.get(i);
                if ((bits & mask) != bits || cost[mask ^ bits] == Long.MAX_VALUE) {
                    continue;
                }
                long candidate = cost[mask ^ bits] + (1L << 40) + candidates.get(i).meters();
                if (candidate < cost[mask]) {
                    cost[mask] = candidate;
                    choice[mask] = candidates.get(i);
                    choiceBits[mask] = bits;
                }
            }
        }
        List<Truck> trucks = new ArrayList<>();
        for (int mask = full; mask != 0; mask ^= choiceBits[mask]) {
            trucks.add(choice[mask]);
        }
        return trucks;
    }

    public record Visit(int node, int trip, long arriveSeconds) {
    }

    /**
     * A qué hora llega el camión a cada contenedor, con la misma cuenta que {@link #schedule}: manejo por la
     * matriz, paradas y descargas. La última hora es la del regreso a la base.
     */
    public static List<Visit> timeline(AreaScenario scenario, Solution solution, Truck truck, int serviceSeconds,
            int unloadSeconds, long startSeconds) {
        TravelMatrix travel = scenario.travel();
        int gate = scenario.gate();
        List<Visit> visits = new ArrayList<>();
        long clock = startSeconds;
        for (int i = 0; i < truck.trips().length; i++) {
            int k = truck.trips()[i];
            int previous = i == 0 ? 0 : gate;
            int length = solution.routeLength(k);
            for (int p = 0; p < length; p++) {
                int node = solution.customer(k, truck.reversed()[i] ? length - 1 - p : p);
                clock += travel.seconds(previous, node);
                visits.add(new Visit(node, i, clock));
                clock += serviceSeconds;
                previous = node;
            }
            clock += travel.seconds(previous, gate) + unloadSeconds;
        }
        return visits;
    }

    private static final class Trips {

        private final TravelMatrix travel;
        private final int gate;
        private final int[][] stops;
        private final int serviceSeconds;

        Trips(AreaScenario scenario, Solution solution, int serviceSeconds) {
            this.travel = scenario.travel();
            this.gate = scenario.gate();
            this.serviceSeconds = serviceSeconds;
            stops = new int[solution.routeCount()][];
            for (int k = 0; k < stops.length; k++) {
                stops[k] = new int[solution.routeLength(k)];
                for (int p = 0; p < stops[k].length; p++) {
                    stops[k][p] = solution.customer(k, p);
                }
            }
        }

        Truck truck(int[] sequence, int unloadSeconds) {
            long meters = 0;
            long seconds = 0;
            boolean[] reversed = new boolean[sequence.length];
            for (int i = 0; i < sequence.length; i++) {
                int from = i == 0 ? 0 : gate;
                long[] forward = leg(stops[sequence[i]], from, false);
                long[] backward = leg(stops[sequence[i]], from, true);
                // Al primer viaje lo dejó así el solver, que lo optimizó saliendo de la base.
                reversed[i] = i > 0 && backward[0] < forward[0];
                long[] chosen = reversed[i] ? backward : forward;
                meters += chosen[0];
                seconds += chosen[1] + unloadSeconds;
            }
            meters += travel.meters(gate, 0);
            seconds += travel.seconds(gate, 0);
            return new Truck(sequence, reversed, meters, seconds);
        }

        /** Segundos del viaje desde la base o la portería, lo que sea más corto, y en su mejor sentido. */
        long shortestSeconds(int trip) {
            long best = Long.MAX_VALUE;
            for (int from : new int[] {0, gate}) {
                best = Math.min(best, Math.min(leg(stops[trip], from, false)[1], leg(stops[trip], from, true)[1]));
            }
            return best;
        }

        /** {metros, segundos} de {@code from} a la portería pasando por las paradas, con el servicio. */
        private long[] leg(int[] path, int from, boolean reversed) {
            long meters = 0;
            long seconds = (long) serviceSeconds * path.length;
            int previous = from;
            for (int p = 0; p < path.length; p++) {
                int stop = reversed ? path[path.length - 1 - p] : path[p];
                meters += travel.meters(previous, stop);
                seconds += travel.seconds(previous, stop);
                previous = stop;
            }
            meters += travel.meters(previous, gate);
            seconds += travel.seconds(previous, gate);
            return new long[] {meters, seconds};
        }
    }
}
