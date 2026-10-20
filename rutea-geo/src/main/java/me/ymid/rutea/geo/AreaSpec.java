package me.ymid.rutea.geo;

/**
 * Qué parte de la red vial entra al grafo, por ids de OSM.
 *
 * @param localityRelation límite de la localidad: entran todas sus calles públicas
 * @param cityRelation límite de la ciudad: entran sus vías principales, para llegar al destino
 * @param destinationWay polígono del sitio de disposición final
 * @param destinationMarginMeters alrededor del destino entran todas las vías, incluidas las internas
 */
public record AreaSpec(long localityRelation, long cityRelation, long destinationWay, int destinationMarginMeters) {

    /**
     * Suba (relación 2214884), Bogotá D. C. (relación 1387968) y el Relleno Sanitario Doña Juana
     * (vía 229080866), en el extracto de {@code data/osm}.
     */
    public static final AreaSpec SUBA = new AreaSpec(2214884, 1387968, 229080866, 1500);

    public AreaSpec {
        if (destinationMarginMeters < 0) {
            throw new IllegalArgumentException("margen negativo: " + destinationMarginMeters);
        }
    }
}
