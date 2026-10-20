package me.ymid.rutea.app.planning;

import java.util.List;

import me.ymid.rutea.domain.WasteStream;

/** Las posiciones van como [lon, lat], el orden que esperan MapLibre y deck.gl. */
public record DemoPlan(Meta meta, double[] depot, List<Container> containers, List<Route> routes) {

    public record Meta(String zone, boolean simulated, String network, int blockMeters, String solver, long seed) {
    }

    public record Container(String id, WasteStream stream, double[] position, double fill, int kg, String route) {
    }

    public record Route(String id, WasteStream stream, int capacityKg, int loadKg, long distanceMeters,
                        List<String> stops, List<double[]> path) {
    }
}
