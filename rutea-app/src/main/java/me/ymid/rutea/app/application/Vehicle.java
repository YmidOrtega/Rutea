package me.ymid.rutea.app.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.ymid.rutea.app.planning.DemoPlan;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.sim.Track;

/** @param arrivals la hora a la que llega a cada parada de {@code stops} */
record Vehicle(String id, WasteStream stream, Track track, List<String> stops, int[] arrivals, double brokenAtSeconds) {

    static final double NOT_BROKEN = Double.POSITIVE_INFINITY;

    Vehicle {
        if (arrivals.length != stops.size()) {
            throw new IllegalArgumentException(id + ": " + arrivals.length + " horas de llegada para " + stops.size()
                    + " paradas");
        }
    }

    static Vehicle of(DemoPlan.Route route) {
        int n = route.path().size();
        double[] lon = new double[n];
        double[] lat = new double[n];
        double[] times = new double[n];
        for (int i = 0; i < n; i++) {
            lon[i] = route.path().get(i)[0];
            lat[i] = route.path().get(i)[1];
            times[i] = route.times()[i];
        }
        return new Vehicle(route.id(), route.stream(), new Track(lon, lat, times), List.copyOf(route.stops()),
                route.arrivals(), NOT_BROKEN);
    }

    Vehicle brokenAt(double seconds) {
        return new Vehicle(id, stream, track, stops, arrivals, seconds);
    }

    /** Las paradas a las que alcanza a llegar, en orden, con su hora; una parada puede repetirse tras un desvío. */
    List<Map.Entry<String, Double>> reached() {
        List<Map.Entry<String, Double>> reached = new ArrayList<>(stops.size());
        for (int k = 0; k < stops.size(); k++) {
            if (arrivals[k] < brokenAtSeconds) {
                reached.add(Map.entry(stops.get(k), (double) arrivals[k]));
            }
        }
        return reached;
    }
}
