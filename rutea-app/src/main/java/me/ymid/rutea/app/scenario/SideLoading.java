package me.ymid.rutea.app.scenario;

import java.util.List;
import java.util.Optional;

/**
 * Carga por capacidad del cargue lateral (ADR 0013): no hay datos de llenado por contenedor, así que cada
 * contenedor carga sus litros × {@link BogotaFigures#LOOSE_WASTE_KG_PER_M3} × un llenado medio al recogerlo, igual en
 * todas las ASE. Cada cifra está en {@code docs/datos.md}.
 */
public final class SideLoading {

    /**
     * {@code PENDIENTE_VERIFICAR}: parte del contenedor llena cuando el camión llega. No hay dato publicado; el 13 %
     * del PPSA de Área Limpia no lo acota, porque no se publica cuántos contenedores tenía ni de qué tamaño. Los planes
     * usan la cota alta, para que nunca se queden cortos de camiones.
     */
    public static final double MEAN_FILL = 1.0;

    /** Los llenados que se miden: el plan se reporta con cada uno. */
    public static final List<Double> FILLS = List.of(0.5, 0.75, 1.0);

    /** Frecuencia que se mide al lado cuando el operador no publica la de sus contenedores. */
    public static final int SENSITIVITY_COLLECTIONS_PER_WEEK = 6;

    /**
     * Lo que se sabe del cargue lateral de una ASE.
     *
     * @param tonnesPerDay toneladas que recoge la ASE por día, según su inspección de la Superservicios; vacío si no
     *     está verificada
     * @param collectionsPerWeek veces por semana que se recogen sus contenedores
     * @param frequencyPublished el operador publica esa frecuencia; si no, es un supuesto y se mide también
     *     {@link #SENSITIVITY_COLLECTIONS_PER_WEEK}
     */
    public record Area(ServiceAreas.ServiceArea area, Optional<Double> tonnesPerDay, int collectionsPerWeek,
            boolean frequencyPublished) {

        public List<Integer> frequencies() {
            return frequencyPublished || collectionsPerWeek == SENSITIVITY_COLLECTIONS_PER_WEEK
                    ? List.of(collectionsPerWeek)
                    : List.of(collectionsPerWeek, SENSITIVITY_COLLECTIONS_PER_WEEK);
        }

        /** Parte de las toneladas de la ASE que recogen sus contenedores. */
        public Optional<Double> share(double kgPerDay) {
            return tonnesPerDay.map(tonnes -> kgPerDay / (tonnes * 1000));
        }
    }

    /**
     * <ul>
     *   <li>ASE 1: el campo {@code Frecuencia} de su capa da 3 para los 168 contenedores en la calle.
     *   <li>ASE 2 y 4: sus capas no traen frecuencia. Se supone 3, la residencial que declaran los dos operadores.
     *   <li>ASE 3: "6 para la atención de contenedores" (Superservicios).
     *   <li>ASE 5: 6, la de su {@link ServiceAreas.Operation}; sus toneladas son {@code PENDIENTE_VERIFICAR}.
     * </ul>
     */
    public static final List<Area> AREAS = List.of(
            new Area(ServiceAreas.ASE_1, Optional.of(1_169.0), 3, true),
            new Area(ServiceAreas.ASE_2, Optional.of(1_804.7), 3, false),
            new Area(ServiceAreas.ASE_3, Optional.of(1_075.0), 6, true),
            new Area(ServiceAreas.ASE_4, Optional.of(725.1), 3, false),
            new Area(ServiceAreas.ASE_5, Optional.empty(), 6, true));

    /** Contenedores negros del Plan de Contenerización de 2026 del ASE 3: 91 de 1.100 L, sin ubicación publicada. */
    public static final int ASE_3_CONTAINERS = 91;

    public static final int ASE_3_LITERS = 1_100;

    /** Contenedores negros del plan de 2026 del ASE 5, de tamaños no publicados. */
    public static final int ASE_5_CONTAINERS = 320;

    private SideLoading() {
    }

    public static Area of(ServiceAreas.ServiceArea area) {
        return AREAS.get(area.number() - 1);
    }

    public static int kgAtCollection(int liters, double fill) {
        if (liters <= 0 || fill <= 0) {
            throw new IllegalArgumentException("litros " + liters + " y llenado " + fill + " tienen que ser positivos");
        }
        return (int) Math.round(liters / 1000.0 * BogotaFigures.LOOSE_WASTE_KG_PER_M3 * fill);
    }

    /** Kilos por día que recogen contenedores con esos litros: la carga de cada recolección repartida en la semana. */
    public static double kgPerDay(List<Integer> liters, int collectionsPerWeek, double fill) {
        long kg = 0;
        for (int l : liters) {
            kg += kgAtCollection(l, fill);
        }
        return kg * collectionsPerWeek / 7.0;
    }
}
