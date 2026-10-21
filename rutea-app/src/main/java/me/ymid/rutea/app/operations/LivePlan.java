package me.ymid.rutea.app.operations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.solver.Solution;

/**
 * El plan en curso, que se puede modificar: por camión, sus viajes con las paradas en el orden en que las hace.
 * Los nodos son índices de {@link SubaScenario#travel}: 0 es la base y {@code gate()} la portería. Las horas se
 * calculan igual que {@link ShiftScheduler}: manejo por la matriz, paradas, descargas y regreso a la base.
 */
final class LivePlan {

    /** Una parada en el plan: el camión, su viaje, la posición en el viaje y la hora de llegada. */
    record Stop(int truck, int trip, int position, int node, long arriveSeconds) {
    }

    private final SubaScenario scenario;
    private final int serviceSeconds;
    private final int unloadSeconds;
    private final int shiftSeconds;
    private final List<List<int[]>> trucks = new ArrayList<>();

    private LivePlan(SubaScenario scenario, int serviceSeconds, int unloadSeconds, int shiftSeconds) {
        this.scenario = scenario;
        this.serviceSeconds = serviceSeconds;
        this.unloadSeconds = unloadSeconds;
        this.shiftSeconds = shiftSeconds;
    }

    static LivePlan of(SubaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule, int serviceSeconds) {
        LivePlan live = new LivePlan(scenario, serviceSeconds, schedule.unloadSeconds(), schedule.shiftSeconds());
        for (ShiftScheduler.Truck truck : schedule.trucks()) {
            List<int[]> trips = new ArrayList<>();
            for (int i = 0; i < truck.trips().length; i++) {
                int k = truck.trips()[i];
                int[] stops = new int[solution.routeLength(k)];
                for (int p = 0; p < stops.length; p++) {
                    stops[p] = solution.customer(k, truck.reversed()[i] ? stops.length - 1 - p : p);
                }
                trips.add(stops);
            }
            live.trucks.add(trips);
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

    int shiftSeconds() {
        return shiftSeconds;
    }

    int serviceSeconds() {
        return serviceSeconds;
    }

    SubaScenario scenario() {
        return scenario;
    }

    /** Desde dónde sale el viaje: la base el primero, la portería los siguientes. */
    int tripStart(int trip) {
        return trip == 0 ? 0 : scenario.gate();
    }

    int tripLoad(int truck, int trip) {
        return Arrays.stream(trip(truck, trip)).map(scenario.instance()::demand).sum();
    }

    List<Stop> timeline(int truck) {
        TravelMatrix travel = scenario.travel();
        List<Stop> stops = new ArrayList<>();
        long clock = 0;
        for (int i = 0; i < tripCount(truck); i++) {
            int previous = tripStart(i);
            int[] trip = trip(truck, i);
            for (int p = 0; p < trip.length; p++) {
                clock += travel.seconds(previous, trip[p]);
                stops.add(new Stop(truck, i, p, trip[p], clock));
                clock += serviceSeconds;
                previous = trip[p];
            }
            clock += travel.seconds(previous, scenario.gate()) + unloadSeconds;
        }
        return stops;
    }

    /** Fin del turno del camión: cuando vuelve a la base. */
    long endSeconds(int truck) {
        TravelMatrix travel = scenario.travel();
        long clock = 0;
        for (int i = 0; i < tripCount(truck); i++) {
            int previous = tripStart(i);
            for (int node : trip(truck, i)) {
                clock += travel.seconds(previous, node) + serviceSeconds;
                previous = node;
            }
            clock += travel.seconds(previous, scenario.gate()) + unloadSeconds;
        }
        return clock + travel.seconds(scenario.gate(), 0);
    }

    long meters(int truck) {
        TravelMatrix travel = scenario.travel();
        long meters = 0;
        for (int i = 0; i < tripCount(truck); i++) {
            int previous = tripStart(i);
            for (int node : trip(truck, i)) {
                meters += travel.meters(previous, node);
                previous = node;
            }
            meters += travel.meters(previous, scenario.gate());
        }
        return meters + travel.meters(scenario.gate(), 0);
    }

    long meters() {
        long total = 0;
        for (int t = 0; t < truckCount(); t++) {
            total += meters(t);
        }
        return total;
    }

    void insert(int truck, int trip, int position, int node) {
        int[] stops = trip(truck, trip);
        int[] grown = new int[stops.length + 1];
        System.arraycopy(stops, 0, grown, 0, position);
        grown[position] = node;
        System.arraycopy(stops, position, grown, position + 1, stops.length - position);
        trucks.get(truck).set(trip, grown);
    }
}
