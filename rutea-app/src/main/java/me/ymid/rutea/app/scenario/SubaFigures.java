package me.ymid.rutea.app.scenario;

import me.ymid.rutea.geo.RoadGraph;

/**
 * Cifras de la operación en Suba (ASE 5) con su fuente. Cada una está en {@code docs/datos.md}; si cambia
 * una, se actualizan las dos en el mismo cambio.
 */
public final class SubaFigures {

    /** Contenedores en vía pública en Bogotá (UAESP, Bogotá.gov.co, 9 de marzo de 2022). */
    public static final int BOGOTA_CONTAINERS = 10_746;

    /**
     * Toneladas diarias del servicio ordinario que entran a Doña Juana, sin puntos críticos (UAESP,
     * Bogotá.gov.co, 17 de abril de 2026; primer trimestre de 2026).
     */
    public static final int BOGOTA_ORDINARY_TONNES_PER_DAY = 5_680;

    /**
     * Parte del ASE 5 en las toneladas de la ciudad: "Área Limpia corresponde al ASE 5 con el 15% del total
     * de la ciudad" (PGIRS, en el informe de inspección de la Superservicios a Área Limpia, marzo de 2026).
     */
    public static final double ASE5_SHARE = 0.15;

    /**
     * Mínimo de toneladas domiciliarias recogidas por cargue lateral, es decir, con contenedores (PPSA de
     * Área Limpia, en el mismo informe de la Superservicios). Se usa el mínimo como estimación.
     */
    public static final double SIDE_LOADING_SHARE = 0.13;

    /** "La mitad para residuos no aprovechables y la mitad para promover el reciclaje" (UAESP, 2025). */
    public static final double NON_RECYCLABLE_CONTAINER_SHARE = 0.5;

    /**
     * Veces por semana que se recoge: el operador declara de 3 a 6 (Superservicios, 2026). Se toma 6, de
     * lunes a sábado, lo que da una carga típica de 7/6 días de generación por recolección.
     */
    public static final int COLLECTIONS_PER_WEEK = 6;

    /**
     * Inicio del turno nocturno: el operador declara 18:00–02:00 como uno de sus dos turnos principales
     * (Superservicios, 2026).
     */
    public static final String SHIFT_START = "18:00";

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
     * Duración del turno: el operador declara turnos de 8 h, como el de 18:00 a 02:00 (Superservicios, 2026).
     */
    public static final int SHIFT_SECONDS = 8 * 3600;

    /**
     * {@code PENDIENTE_VERIFICAR}: rango de la descarga en Doña Juana. "En promedio, gastan entre 40 minutos a una
     * hora y media" (El Tiempo, 3 de junio de 2022). Es prensa, no un dato oficial de la UAESP o el operador.
     */
    public static final int UNLOAD_SECONDS_LOW = 40 * 60;

    public static final int UNLOAD_SECONDS_HIGH = 90 * 60;

    /** Compactador más común del ASE 5: 17 de 32 yardas, 8 toneladas (Superservicios, 2026). */
    public static final int COMPACTOR_CAPACITY_KG = 8_000;

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

    /** Supuesto: vehículo sin compactación de una organización de recicladores (R06, R10), en kg. */
    public static final int RECYCLABLE_VEHICLE_KG = 1_500;

    /** Supuesto: minutos de descarga y pesaje en la ECA (R10). */
    public static final int ECA_UNLOAD_SECONDS = 20 * 60;

    private SubaFigures() {
    }

    /** Contenedores blancos: la otra mitad de los de Suba, "la mitad para promover el reciclaje" (UAESP, 2025). */
    public static int recyclableContainers() {
        return (int) Math.round(BOGOTA_CONTAINERS * ASE5_SHARE * (1 - NON_RECYCLABLE_CONTAINER_SHARE));
    }

    /**
     * Supuesto: kilos de aprovechables por día en los contenedores blancos, con la misma proporción que en la
     * ciudad: lo aprovechado (16 %) frente a lo dispuesto (84 %), sobre lo que pasa por los negros.
     */
    public static long recyclableKgPerDay() {
        return Math.round(containerKgPerDay() * RECOVERED_SHARE / (1 - RECOVERED_SHARE));
    }

    public static double meanRecyclableKgPerCollection(int containers) {
        return recyclableKgPerDay() * 7.0 / COLLECTIONS_PER_WEEK / containers;
    }

    /**
     * Supuesto: la ECA de Suba. No hay inventario público de ECA con coordenadas (está en las Resoluciones UAESP
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

    /**
     * Contenedores negros en Suba: 10.746 × 15 % × 1/2 = 806. Supone que los contenedores se reparten
     * entre las ASE como las toneladas, porque no hay inventario por localidad publicado.
     */
    public static int nonRecyclableContainers() {
        return (int) Math.round(BOGOTA_CONTAINERS * ASE5_SHARE * NON_RECYCLABLE_CONTAINER_SHARE);
    }

    /** Kilos por día que pasan por los contenedores de Suba: 5.680 t × 15 % × 13 % = 110.760 kg. */
    public static long containerKgPerDay() {
        return Math.round(BOGOTA_ORDINARY_TONNES_PER_DAY * 1000.0 * ASE5_SHARE * SIDE_LOADING_SHARE);
    }

    /** Carga media de un contenedor en cada recolección, en kg. */
    public static double meanKgPerCollection(int containers) {
        return containerKgPerDay() * 7.0 / COLLECTIONS_PER_WEEK / containers;
    }
}
