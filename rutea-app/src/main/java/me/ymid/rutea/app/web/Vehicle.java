package me.ymid.rutea.app.web;

import java.util.ArrayList;
import java.util.List;

import me.ymid.rutea.app.planning.DemoPlan;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.sim.Track;

record Vehicle(String id, WasteStream stream, Track track, List<String> stops, double brokenAtSeconds) {

    static final double NOT_BROKEN = Double.POSITIVE_INFINITY;

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
                NOT_BROKEN);
    }

    Vehicle brokenAt(double seconds) {
        return new Vehicle(id, stream, track, stops, seconds);
    }

    /**
     * La hora en que llega a cada contenedor que alcanza a recoger, en el orden de {@link #stops}. Cada parada es un
     * punto repetido del recorrido; las que caen en un sitio de descarga no son contenedores.
     */
    List<Double> arrivals(List<double[]> unloadSites) {
        double[] lon = track.lon();
        double[] lat = track.lat();
        double[] times = track.times();
        List<Double> arrivals = new ArrayList<>(stops.size());
        for (int i = 0; i + 1 < times.length; i++) {
            if (lon[i] != lon[i + 1] || lat[i] != lat[i + 1] || times[i + 1] <= times[i] || unloads(lon[i], lat[i],
                    unloadSites)) {
                continue;
            }
            arrivals.add(times[i]);
        }
        if (arrivals.size() != stops.size()) {
            throw new IllegalStateException(id + ": " + arrivals.size() + " paradas en el recorrido para "
                    + stops.size() + " contenedores");
        }
        return arrivals.stream().filter(seconds -> seconds < brokenAtSeconds).toList();
    }

    private static boolean unloads(double lon, double lat, List<double[]> sites) {
        for (double[] site : sites) {
            if (site[0] == lon && site[1] == lat) {
                return true;
            }
        }
        return false;
    }
}
