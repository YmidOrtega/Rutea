package me.ymid.rutea.app.planning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.Solution;

/**
 * Reparte los viajes de una solución entre camiones, para que un mismo compactador haga varios en su turno:
 * base → viaje → descarga en el relleno → viaje → descarga → base. El primer viaje de cada camión sale de la
 * base y los siguientes, de la portería; cada uno se recorre en el sentido que mide menos.
 *
 * <p>La asignación es exacta: programación dinámica sobre los subconjuntos de viajes, que minimiza primero los
 * camiones y después los km. Con {@code maxTripsPerTruck = 1} da un camión por viaje.
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

    public record Schedule(List<Truck> trucks, int unloadSeconds, int shiftSeconds) {

        public Schedule {
            trucks = List.copyOf(trucks);
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

    public static Schedule schedule(SubaScenario scenario, Solution solution, int serviceSeconds, int unloadSeconds,
            int shiftSeconds, int maxTripsPerTruck) {
        int n = solution.routeCount();
        if (n > MAX_EXACT_TRIPS) {
            throw new IllegalArgumentException(n + " viajes; la asignación exacta admite hasta " + MAX_EXACT_TRIPS);
        }
        if (maxTripsPerTruck < 1 || maxTripsPerTruck > 3) {
            throw new IllegalArgumentException("de 1 a 3 viajes por camión, no " + maxTripsPerTruck);
        }
        Trips trips = new Trips(scenario, solution, serviceSeconds);

        // Todas las secuencias de viajes que caben en un turno, agrupadas por su viaje de menor índice.
        List<List<Truck>> byLowest = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byLowest.add(new ArrayList<>());
        }
        for (int a = 0; a < n; a++) {
            byLowest.get(a).add(trips.truck(new int[] {a}, unloadSeconds));
            for (int b = 0; maxTripsPerTruck >= 2 && b < n; b++) {
                if (b == a) {
                    continue;
                }
                Truck pair = trips.truck(new int[] {a, b}, unloadSeconds);
                if (pair.seconds() > shiftSeconds) {
                    continue;
                }
                byLowest.get(Math.min(a, b)).add(pair);
                for (int c = 0; maxTripsPerTruck >= 3 && c < n; c++) {
                    if (c == a || c == b) {
                        continue;
                    }
                    Truck triple = trips.truck(new int[] {a, b, c}, unloadSeconds);
                    if (triple.seconds() <= shiftSeconds) {
                        byLowest.get(Math.min(a, Math.min(b, c))).add(triple);
                    }
                }
            }
        }

        // costo[mask] = camiones · 2^40 + metros: comparar el long es comparar primero camiones y luego km.
        int full = (1 << n) - 1;
        long[] cost = new long[full + 1];
        Truck[] choice = new Truck[full + 1];
        Arrays.fill(cost, Long.MAX_VALUE);
        cost[0] = 0;
        for (int mask = 1; mask <= full; mask++) {
            int lowest = Integer.numberOfTrailingZeros(mask);
            for (Truck truck : byLowest.get(lowest)) {
                int bits = bits(truck.trips());
                if ((bits & mask) != bits || cost[mask ^ bits] == Long.MAX_VALUE) {
                    continue;
                }
                long candidate = cost[mask ^ bits] + (1L << 40) + truck.meters();
                if (candidate < cost[mask]) {
                    cost[mask] = candidate;
                    choice[mask] = truck;
                }
            }
        }
        List<Truck> trucks = new ArrayList<>();
        for (int mask = full; mask != 0; mask ^= bits(choice[mask].trips())) {
            trucks.add(choice[mask]);
        }
        // El orden de salida sigue el del primer viaje de cada camión: es determinista y fácil de leer.
        trucks.sort((x, y) -> Integer.compare(x.trips()[0], y.trips()[0]));
        return new Schedule(trucks, unloadSeconds, shiftSeconds);
    }

    /** Una parada en la línea de tiempo: el nodo, el viaje del camión y la hora de llegada desde el inicio. */
    public record Visit(int node, int trip, long arriveSeconds) {
    }

    /**
     * A qué hora llega el camión a cada contenedor, con la misma cuenta que {@link #schedule}: manejo por la
     * matriz, paradas y descargas. La última hora es la del regreso a la base.
     */
    public static List<Visit> timeline(SubaScenario scenario, Solution solution, Truck truck, int serviceSeconds,
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

    private static int bits(int[] trips) {
        int bits = 0;
        for (int t : trips) {
            bits |= 1 << t;
        }
        return bits;
    }

    /** Metros y segundos de cada viaje, saliendo de la base o de la portería, en cada sentido. */
    private static final class Trips {

        private final TravelMatrix travel;
        private final int gate;
        private final int[][] stops;
        private final int serviceSeconds;

        Trips(SubaScenario scenario, Solution solution, int serviceSeconds) {
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
