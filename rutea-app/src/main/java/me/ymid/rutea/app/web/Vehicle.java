package me.ymid.rutea.app.web;

import me.ymid.rutea.app.planning.DemoPlan;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.sim.Track;

/** Un vehículo en la calle: su recorrido vigente y, si se averió, desde cuándo está detenido. */
record Vehicle(String id, WasteStream stream, Track track, double brokenAtSeconds) {

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
        return new Vehicle(route.id(), route.stream(), new Track(lon, lat, times), NOT_BROKEN);
    }

    Vehicle brokenAt(double seconds) {
        return new Vehicle(id, stream, track, seconds);
    }
}
