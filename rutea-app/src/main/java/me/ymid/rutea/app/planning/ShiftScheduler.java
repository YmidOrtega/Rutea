package me.ymid.rutea.app.planning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * exactos por separado, y si el reparto pasa de la cota inferior ({@link Bound}), una ramificación y poda busca uno con
 * menos camiones. El reparto lleva la cota: si son iguales, es el mínimo. Con {@code maxTripsPerTruck = 1} da un camión
 * por viaje.
 *
 * <p>Varios viajes solo se encadenan si caben en el turno. Un viaje que ni solo cabe no se descarta: queda en
 * su propio camión, marcado como horas extra, porque eso también es un dato del plan.
 */
public final class ShiftScheduler {

    /** 2^20 subconjuntos: más viajes que esto ya no se resuelven exactos en un tiempo razonable. */
    static final int MAX_EXACT_TRIPS = 20;

    /** Presupuesto de la ramificación y poda con más viajes; sin agotarlo, termina en milisegundos. */
    static final long SEARCH_NODES = 2_000_000;

    /** Lo más que admite la asignación; cada operación fija el suyo ({@code Operation#maxTripsPerTruck}). */
    public static final int MAX_TRIPS_PER_TRUCK = 4;

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
        if (maxTripsPerTruck < 1 || maxTripsPerTruck > MAX_TRIPS_PER_TRUCK) {
            throw new IllegalArgumentException("de 1 a " + MAX_TRIPS_PER_TRUCK + " viajes por camión, no "
                    + maxTripsPerTruck);
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
        int lowerBound = n <= MAX_EXACT_TRIPS ? trucks.size()
                : lowerBound(trips, n, unloadSeconds, shiftSeconds, maxTripsPerTruck);
        if (trucks.size() > lowerBound && n < Long.SIZE) {
            Search search = new Search(trips, n, unloadSeconds, shiftSeconds, maxTripsPerTruck, trucks.size());
            if (search.best != null) {
                trucks = new ArrayList<>(search.best);
            }
            lowerBound = search.complete ? trucks.size() : Math.max(lowerBound, search.rootBound());
        }
        // El orden de salida sigue el del primer viaje de cada camión: es determinista y fácil de leer.
        trucks.sort((x, y) -> Integer.compare(x.trips()[0], y.trips()[0]));
        return new Schedule(trucks, unloadSeconds, shiftSeconds, lowerBound);
    }

    /**
     * Ramificación y poda sobre los conjuntos de viajes que caben en un turno, para más de {@link #MAX_EXACT_TRIPS}
     * viajes: cubre siempre el viaje más bajo que falta, con los conjuntos más grandes primero, y poda con la cota de
     * {@link Bound} sobre los viajes que faltan. Solo busca repartos con menos camiones que el de los grupos; si
     * recorre todo el árbol sin pasar de {@link #SEARCH_NODES} nodos, el mejor que encontró es el mínimo.
     */
    private static final class Search {

        private final Bound bound;
        private final long[][] bits;
        private final Truck[][] trucks;
        private final long full;
        private final Truck[] stack;
        private long nodes;
        private int limit;
        List<Truck> best;
        boolean complete = true;

        Search(Trips trips, int n, int unloadSeconds, int shiftSeconds, int maxTripsPerTruck, int upperBound) {
            int[] ids = new int[n];
            Arrays.setAll(ids, i -> i);
            Map<Long, Truck> all = patterns(trips, ids, unloadSeconds, shiftSeconds, maxTripsPerTruck);
            bound = new Bound(trips, n, unloadSeconds, shiftSeconds, maxTripsPerTruck, all.keySet());
            List<List<Map.Entry<Long, Truck>>> byLowest = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                byLowest.add(new ArrayList<>());
            }
            for (Map.Entry<Long, Truck> pattern : all.entrySet()) {
                byLowest.get(Long.numberOfTrailingZeros(pattern.getKey())).add(pattern);
            }
            bits = new long[n][];
            trucks = new Truck[n][];
            for (int i = 0; i < n; i++) {
                List<Map.Entry<Long, Truck>> candidates = byLowest.get(i);
                candidates.sort((a, b) -> Long.bitCount(a.getKey()) != Long.bitCount(b.getKey())
                        ? Integer.compare(Long.bitCount(b.getKey()), Long.bitCount(a.getKey()))
                        : Long.compare(a.getValue().meters(), b.getValue().meters()));
                bits[i] = candidates.stream().mapToLong(Map.Entry::getKey).toArray();
                trucks[i] = candidates.stream().map(Map.Entry::getValue).toArray(Truck[]::new);
            }
            full = n == Long.SIZE - 1 ? Long.MAX_VALUE : (1L << n) - 1;
            stack = new Truck[n];
            limit = upperBound;
            search(0, 0);
        }

        int rootBound() {
            return bound.trucks(full);
        }

        private void search(long covered, int used) {
            if (covered == full) {
                limit = used;
                best = List.of(Arrays.copyOf(stack, used));
                return;
            }
            if (++nodes > SEARCH_NODES) {
                complete = false;
                return;
            }
            if (used + bound.trucks(full & ~covered) >= limit) {
                return;
            }
            int lowest = Long.numberOfTrailingZeros(~covered);
            for (int c = 0; c < bits[lowest].length && complete; c++) {
                long pattern = bits[lowest][c];
                if ((pattern & covered) == 0) {
                    stack[used] = trucks[lowest][c];
                    search(covered | pattern, used + 1);
                }
            }
        }
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
     * Agrega a {@code prefix} cada viaje que falta, si la secuencia todavía cabe en el turno. Agregar un viaje nunca
     * acorta el turno, así que una secuencia que no cabe no se extiende.
     */
    private static void extend(Trips trips, int[] ids, int[] prefix, long bits, int unloadSeconds, int shiftSeconds,
            int maxTripsPerTruck, Map<Long, Truck> best) {
        for (int next = 0; next < ids.length; next++) {
            if ((bits & 1L << next) != 0) {
                continue;
            }
            int[] positions = Arrays.copyOf(prefix, prefix.length + 1);
            positions[prefix.length] = next;
            int[] sequence = new int[positions.length];
            for (int i = 0; i < positions.length; i++) {
                sequence[i] = ids[positions[i]];
            }
            Truck truck = trips.truck(sequence, unloadSeconds);
            if (truck.seconds() > shiftSeconds) {
                continue;
            }
            long withNext = bits | 1L << next;
            Truck current = best.get(withNext);
            if (current == null || truck.meters() < current.meters()) {
                best.put(withNext, truck);
            }
            if (positions.length < maxTripsPerTruck) {
                extend(trips, ids, positions, withNext, unloadSeconds, shiftSeconds, maxTripsPerTruck, best);
            }
        }
    }

    /** {@link Bound} sobre todos los viajes; con más de 63, sin la búsqueda, solo la cuenta. */
    private static int lowerBound(Trips trips, int n, int unloadSeconds, int shiftSeconds, int maxTripsPerTruck) {
        if (n >= Long.SIZE) {
            return (n + maxTripsPerTruck - 1) / maxTripsPerTruck;
        }
        return new Bound(trips, n, unloadSeconds, shiftSeconds, maxTripsPerTruck).trucks((1L << n) - 1);
    }

    /**
     * Ningún reparto de un conjunto de viajes usa menos camiones. Cada viaje que ni solo cabe va en su propio camión,
     * y cada uno de los demás hace a lo sumo {@code maxTripsPerTruck} viajes, o menos si ni los más rápidos caben. Además, cada camión vuelve a la base al
     * final, y solo su primer viaje sale de la base: los demás salen de la portería. Con {@code k} camiones, el tiempo
     * es al menos el de todos los viajes desde la portería (en su sentido más rápido), menos lo que ahorran los
     * {@code k} viajes que más ganan saliendo de la base, más {@code k} regresos, y tiene que caber en {@code k}
     * turnos.
     */
    private static final class Bound {

        private final int shiftSeconds;
        private final int maxTripsPerTruck;
        private final long returnSeconds;
        private final long overtime;
        /** Los viajes que caben, de más a menos ahorro al salir de la base. */
        private final int[] bySaving;
        /** Los que aparecen en algún conjunto de {@code maxTripsPerTruck} viajes que cabe; todos si no se sabe. */
        private long fullSetTrips = -1;
        private final long[] fromGate;
        private final long[] saving;

        Bound(Trips trips, int n, int unloadSeconds, int shiftSeconds, int maxTripsPerTruck) {
            this(trips, n, unloadSeconds, shiftSeconds, maxTripsPerTruck, List.of());
        }

        /**
         * Con los conjuntos de viajes que caben en un turno ({@link #patterns}), el tope de viajes por camión es el del
         * conjunto más grande, y solo los viajes de esos conjuntos pueden ir en un camión lleno.
         */
        Bound(Trips trips, int n, int unloadSeconds, int shiftSeconds, int maxTripsPerTruck,
                Collection<Long> patterns) {
            this.shiftSeconds = shiftSeconds;
            returnSeconds = trips.returnSeconds();
            fromGate = new long[n];
            saving = new long[n];
            long late = 0;
            List<Integer> onTime = new ArrayList<>();
            for (int t = 0; t < n; t++) {
                if (trips.truck(new int[] {t}, unloadSeconds).seconds() > shiftSeconds) {
                    late |= 1L << t;
                    continue;
                }
                fromGate[t] = trips.fastestSeconds(t, true) + unloadSeconds;
                saving[t] = Math.max(0, trips.fastestSeconds(t, true) - trips.fastestSeconds(t, false));
                onTime.add(t);
            }
            overtime = late;
            onTime.sort((a, b) -> saving[a] != saving[b] ? Long.compare(saving[b], saving[a]) : Integer.compare(a, b));
            bySaving = onTime.stream().mapToInt(Integer::intValue).toArray();
            int cap = fitting(onTime, maxTripsPerTruck);
            if (!patterns.isEmpty()) {
                cap = patterns.stream().mapToInt(Long::bitCount).max().orElseThrow();
                long members = 0;
                for (long pattern : patterns) {
                    if (Long.bitCount(pattern) == cap) {
                        members |= pattern;
                    }
                }
                fullSetTrips = members;
            }
            this.maxTripsPerTruck = cap;
        }

        /**
         * Lo más que encadena un camión: {@code k} viajes caben en un turno solo si caben los {@code k} más rápidos
         * desde la portería, con el mayor ahorro de salir de la base y el regreso.
         */
        private int fitting(List<Integer> onTime, int maxTripsPerTruck) {
            long[] fastest = onTime.stream().mapToLong(t -> fromGate[t]).sorted().toArray();
            long bestSaving = onTime.isEmpty() ? 0 : saving[bySaving[0]];
            long seconds = returnSeconds - bestSaving;
            int fits = 1;
            for (int k = 1; k <= Math.min(maxTripsPerTruck, fastest.length); k++) {
                seconds += fastest[k - 1];
                if (seconds > shiftSeconds) {
                    break;
                }
                fits = k;
            }
            return fits;
        }

        /**
         * Con {@code k} camiones, al menos {@code count - (cap - 1)·k} van llenos, cada uno con {@code cap} viajes
         * distintos de los que pueden ir en un camión lleno.
         */
        private boolean fullTrucks(long left, int k) {
            int full = Long.bitCount(left) - (maxTripsPerTruck - 1) * k;
            return full <= 0 || (long) full * maxTripsPerTruck <= Long.bitCount(left & fullSetTrips);
        }

        int trucks(long trips) {
            int late = Long.bitCount(overtime & trips);
            long left = trips & ~overtime;
            int count = Long.bitCount(left);
            if (count == 0) {
                return late;
            }
            long total = 0;
            for (long rest = left; rest != 0; rest &= rest - 1) {
                total += fromGate[Long.numberOfTrailingZeros(rest)];
            }
            int k = (count + maxTripsPerTruck - 1) / maxTripsPerTruck;
            long saved = 0;
            int taken = 0;
            int next = 0;
            while (true) {
                while (taken < k) {
                    while ((left & 1L << bySaving[next]) == 0) {
                        next++;
                    }
                    saved += saving[bySaving[next++]];
                    taken++;
                }
                // Con un camión por viaje siempre alcanza: cada viaje solo cabe en el turno.
                if (k == count || (total - saved + k * returnSeconds <= (long) k * shiftSeconds && fullTrucks(left, k))) {
                    return late + k;
                }
                k++;
            }
        }
    }

    /**
     * Por cada conjunto de viajes de {@code ids} que cabe en un turno, su orden de menos metros, con el bit {@code i}
     * por {@code ids[i]}. Cada viaje solo también, aunque no quepa.
     */
    private static Map<Long, Truck> patterns(Trips trips, int[] ids, int unloadSeconds, int shiftSeconds,
            int maxTripsPerTruck) {
        Map<Long, Truck> best = new LinkedHashMap<>();
        for (int a = 0; a < ids.length; a++) {
            best.put(1L << a, trips.truck(new int[] {ids[a]}, unloadSeconds));
            if (maxTripsPerTruck >= 2) {
                extend(trips, ids, new int[] {a}, 1L << a, unloadSeconds, shiftSeconds, maxTripsPerTruck, best);
            }
        }
        return best;
    }

    /** Programación dinámica sobre los subconjuntos de {@code ids}: primero menos camiones, después menos km. */
    private static List<Truck> exact(Trips trips, int[] ids, int unloadSeconds, int shiftSeconds,
            int maxTripsPerTruck) {
        int n = ids.length;
        Map<Long, Truck> best = patterns(trips, ids, unloadSeconds, shiftSeconds, maxTripsPerTruck);
        List<List<Truck>> byLowest = new ArrayList<>();
        List<List<Integer>> bitsByLowest = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byLowest.add(new ArrayList<>());
            bitsByLowest.add(new ArrayList<>());
        }
        best.forEach((bits, truck) -> {
            int lowest = Long.numberOfTrailingZeros(bits);
            byLowest.get(lowest).add(truck);
            bitsByLowest.get(lowest).add((int) (long) bits);
        });

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
        private final int serviceSeconds;
        /** {metros, segundos} de cada viaje: [viaje][desde la base, desde la portería][al derecho, al revés]. */
        private final long[][][][] legs;

        Trips(AreaScenario scenario, Solution solution, int serviceSeconds) {
            this.travel = scenario.travel();
            this.gate = scenario.gate();
            this.serviceSeconds = serviceSeconds;
            legs = new long[solution.routeCount()][2][2][];
            for (int k = 0; k < legs.length; k++) {
                int[] stops = new int[solution.routeLength(k)];
                for (int p = 0; p < stops.length; p++) {
                    stops[p] = solution.customer(k, p);
                }
                for (int from = 0; from < 2; from++) {
                    legs[k][from][0] = leg(stops, from == 0 ? 0 : gate, false);
                    legs[k][from][1] = leg(stops, from == 0 ? 0 : gate, true);
                }
            }
        }

        Truck truck(int[] sequence, int unloadSeconds) {
            long meters = 0;
            long seconds = 0;
            boolean[] reversed = new boolean[sequence.length];
            for (int i = 0; i < sequence.length; i++) {
                long[] forward = legs[sequence[i]][i == 0 ? 0 : 1][0];
                long[] backward = legs[sequence[i]][i == 0 ? 0 : 1][1];
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

        long fastestSeconds(int trip, boolean fromGate) {
            long[][] from = legs[trip][fromGate ? 1 : 0];
            return Math.min(from[0][1], from[1][1]);
        }

        long returnSeconds() {
            return travel.seconds(gate, 0);
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
