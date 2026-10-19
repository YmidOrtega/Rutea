package me.ymid.rutea.app.planning;

import java.util.List;

import me.ymid.rutea.domain.WasteStream;

/**
 * Plan de recolección simulado que consume el mapa de demo ({@code web/public/demo/plan-suba.json}).
 * Las posiciones van como {@code [lon, lat]}, el orden que esperan MapLibre y deck.gl.
 */
public record DemoPlan(Meta meta, double[] depot, List<Container> containers, List<Route> routes) {

    /**
     * @param network     descripción de la red sobre la que se trazan las rutas
     * @param blockMeters lado de cada manzana de la cuadrícula
     */
    public record Meta(String zone, boolean simulated, String network, int blockMeters, String solver, long seed) {
    }

    /**
     * @param fill llenado entre 0 y 1
     * @param kg   residuos que se recogen en la parada
     */
    public record Container(String id, WasteStream stream, double[] position, double fill, int kg, String route) {
    }

    /**
     * @param distanceMeters costo de la ruta según el {@code Evaluator}
     * @param stops          IDs de los contenedores en orden de visita
     * @param path           recorrido por la cuadrícula, del depósito al depósito
     */
    public record Route(String id, WasteStream stream, int capacityKg, int loadKg, long distanceMeters,
                        List<String> stops, List<double[]> path) {
    }
}
