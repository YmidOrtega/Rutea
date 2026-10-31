package me.ymid.rutea.app.operations;

import java.util.ArrayList;
import java.util.List;

import me.ymid.rutea.geo.TravelMatrix;

/**
 * Inserción al menor costo de contenedores sueltos (huérfanos de una avería, un desborde) en los camiones que siguen
 * en servicio. Solo se inserta en la parte del recorrido que el camión todavía no hizo, sin pasar la capacidad del
 * viaje ni el turno. Lo que no cabe queda para la reserva.
 */
final class Insertion {

    record Placement(int node, int truck, int trip, long extraMeters, long extraSeconds) {
    }

    private Insertion() {
    }

    /**
     * Inserta, uno a uno, el contenedor que menos metros agrega en su mejor lugar, hasta que ninguno quepa. Modifica
     * {@code live} y devuelve lo que insertó; los que no caben quedan en {@code pending}.
     *
     * @param excluded camión que no recibe nada (el averiado); -1 si ninguno
     */
    static List<Placement> cheapest(LivePlan live, List<Integer> pending, long atSeconds, int excluded) {
        int gate = live.scenario().gate();
        int capacity = live.scenario().instance().capacity();
        List<Placement> placed = new ArrayList<>();
        while (!pending.isEmpty()) {
            long bestMeters = Long.MAX_VALUE;
            int bestOrphan = -1;
            int bestTruck = -1;
            int bestTrip = -1;
            int bestPosition = -1;
            long bestSeconds = 0;
            for (int t = 0; t < live.truckCount(); t++) {
                if (t == excluded) {
                    continue;
                }
                long slack = live.shiftSeconds() - live.endSeconds(t);
                if (slack <= 0) {
                    continue;
                }
                long[][] arrive = arrivals(live, t);
                for (int i = 0; i < live.tripCount(t); i++) {
                    int room = capacity - live.tripLoad(t, i);
                    int[] trip = live.trip(t, i);
                    for (int p = 0; p <= trip.length; p++) {
                        // Futura: después de una parada a la que el camión aún no llega, o antes de la primera si
                        // tampoco llegó a ella.
                        if (trip.length == 0 || arrive[i][p == 0 ? 0 : p - 1] <= atSeconds) {
                            continue;
                        }
                        TravelMatrix travel = live.travelAt(atSeconds);
                        int previous = p == 0 ? live.tripStart(i) : trip[p - 1];
                        int next = p == trip.length ? gate : trip[p];
                        for (int o = 0; o < pending.size(); o++) {
                            int node = pending.get(o);
                            if (live.scenario().instance().demand(node) > room) {
                                continue;
                            }
                            long meters = travel.meters(previous, node) + travel.meters(node, next)
                                    - travel.meters(previous, next);
                            long seconds = travel.seconds(previous, node) + travel.seconds(node, next)
                                    - travel.seconds(previous, next) + live.serviceSeconds();
                            if (seconds <= slack && meters < bestMeters) {
                                bestMeters = meters;
                                bestSeconds = seconds;
                                bestOrphan = o;
                                bestTruck = t;
                                bestTrip = i;
                                bestPosition = p;
                            }
                        }
                    }
                }
            }
            if (bestOrphan < 0) {
                break;
            }
            int node = pending.remove(bestOrphan);
            live.insert(bestTruck, bestTrip, bestPosition, node);
            placed.add(new Placement(node, bestTruck, bestTrip, bestMeters, bestSeconds));
        }
        return placed;
    }

    private static long[][] arrivals(LivePlan live, int truck) {
        long[][] arrive = new long[live.tripCount(truck)][];
        for (int i = 0; i < arrive.length; i++) {
            arrive[i] = new long[live.trip(truck, i).length];
        }
        for (LivePlan.Stop stop : live.timeline(truck)) {
            arrive[stop.trip()][stop.position()] = stop.arriveSeconds();
        }
        return arrive;
    }
}
