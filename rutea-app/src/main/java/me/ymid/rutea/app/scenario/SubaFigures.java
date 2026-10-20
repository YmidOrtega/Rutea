package me.ymid.rutea.app.scenario;

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

    /** Compactador más común del ASE 5: 17 de 32 yardas, 8 toneladas (Superservicios, 2026). */
    public static final int COMPACTOR_CAPACITY_KG = 8_000;

    private SubaFigures() {
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
