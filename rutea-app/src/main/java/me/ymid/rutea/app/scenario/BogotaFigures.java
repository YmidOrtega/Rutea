package me.ymid.rutea.app.scenario;

import me.ymid.rutea.geo.RoadGraph;

/**
 * Cifras de toda Bogotá y supuestos que valen para cualquier ASE, con su fuente. Las de cada operador están en
 * {@link ServiceAreas.Operation}. Cada una está en {@code docs/datos.md}; si cambia una, se actualizan las dos en
 * el mismo cambio.
 */
public final class BogotaFigures {

    /** Contenedores en vía pública en Bogotá (UAESP, Bogotá.gov.co, 9 de marzo de 2022). */
    public static final int BOGOTA_CONTAINERS = 10_746;

    /**
     * Toneladas diarias del servicio ordinario que entran a Doña Juana, sin puntos críticos (UAESP,
     * Bogotá.gov.co, 17 de abril de 2026; primer trimestre de 2026).
     */
    public static final int BOGOTA_ORDINARY_TONNES_PER_DAY = 5_680;

    /** "La mitad para residuos no aprovechables y la mitad para promover el reciclaje" (UAESP, 2025). */
    public static final double NON_RECYCLABLE_CONTAINER_SHARE = 0.5;

    /**
     * {@code PENDIENTE_VERIFICAR}: segundos detenido en cada contenedor. No hay estudio publicado para Bogotá;
     * los fabricantes anuncian de 6 a 10 s por ciclo del brazo de cargue lateral (Heil, Bucher), que es solo
     * una cota inferior: la parada también incluye acercarse, alinearse y revisar. Se supone 60 s.
     */
    public static final int SERVICE_SECONDS_PER_CONTAINER = 60;

    /**
     * {@code PENDIENTE_VERIFICAR}: litros de diésel por km de un compactador en recolección. No hay cifra
     * publicada para Bogotá; se reemplaza con datos del operador. Como el CO₂ sale proporcional a los km, el
     * porcentaje de ahorro entre dos planes no depende de este valor; las toneladas absolutas sí.
     */
    public static final double LITERS_PER_KM = 0.8;

    /**
     * {@code PENDIENTE_VERIFICAR}: rango de la descarga en Doña Juana. "En promedio, gastan entre 40 minutos a una
     * hora y media" (El Tiempo, 3 de junio de 2022). Es prensa, no un dato oficial de la UAESP o el operador.
     */
    public static final int UNLOAD_SECONDS_LOW = 40 * 60;

    public static final int UNLOAD_SECONDS_HIGH = 90 * 60;

    /**
     * {@code PENDIENTE_VERIFICAR}: parte de lo recogido que se aprovecha. "De los residuos recogidos cada día, se
     * logran aprovechar solo el 16%" (El Espectador, 18 de septiembre de 2025; en la bajada, sin fuente explícita).
     */
    public static final double RECOVERED_SHARE = 0.16;

    /**
     * {@code PENDIENTE_VERIFICAR}: segundos desde la avería hasta que un camión de reserva sale de la base
     * (avisar, alistar tripulación y vehículo). Sin dato del operador.
     */
    public static final int RESERVE_DISPATCH_SECONDS = 15 * 60;

    /**
     * {@code PENDIENTE_VERIFICAR}: densidad de los residuos sueltos en un contenedor. No hay cifra publicada para
     * Bogotá; 150 kg/m³ es la de manual para "basura suelta en recipientes" (Tchobanoglous, 1993, citado por el
     * estudio de caracterización de CONAMA RM y la PUCV, 2006).
     */
    public static final double LOOSE_WASTE_KG_PER_M3 = 150;

    /** Supuesto: vehículo sin compactación de una organización de recicladores (R06, R10), en kg. */
    public static final int RECYCLABLE_VEHICLE_KG = 1_500;

    /** Supuesto: minutos de descarga y pesaje en la ECA (R10). */
    public static final int ECA_UNLOAD_SECONDS = 20 * 60;

    private BogotaFigures() {
    }

    /**
     * Supuesto: la ECA de la zona. No hay inventario público de ECA con coordenadas (está en las Resoluciones UAESP
     * 137 y 698 de 2023, no publicadas) ni ECA identificadas en OSM, así que se toma el vértice de calle local más
     * cercano al centro del rectángulo de la localidad.
     */
    public static int ecaVertex(RoadGraph graph) {
        int minLat = Integer.MAX_VALUE;
        int maxLat = Integer.MIN_VALUE;
        int minLon = Integer.MAX_VALUE;
        int maxLon = Integer.MIN_VALUE;
        for (int s = 0; s < graph.shapeCount(); s++) {
            if (!graph.shapeInLocality(s)) {
                continue;
            }
            for (int p = graph.firstShapePoint(s); p < graph.endShapePoint(s); p++) {
                minLat = Math.min(minLat, graph.shapeLatE7(p));
                maxLat = Math.max(maxLat, graph.shapeLatE7(p));
                minLon = Math.min(minLon, graph.shapeLonE7(p));
                maxLon = Math.max(maxLon, graph.shapeLonE7(p));
            }
        }
        return graph.nearestVertex((int) (((long) minLat + maxLat) / 2), (int) (((long) minLon + maxLon) / 2));
    }
}
