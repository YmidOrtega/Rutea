package me.ymid.rutea.app.scenario;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Carga por capacidad del cargue lateral (ADR 0013): no hay datos de llenado por contenedor, así que cada
 * contenedor carga sus litros × {@link BogotaFigures#LOOSE_WASTE_KG_PER_M3} × un llenado medio al recogerlo, igual en
 * todas las ASE. Cada cifra está en {@code docs/datos.md}.
 */
public final class SideLoading {

    /**
     * {@code PENDIENTE_VERIFICAR}: parte del contenedor llena cuando el camión llega. No hay dato publicado; el 13 %
     * del PPSA de Torca no lo acota, porque no se publica cuántos contenedores tenía ni de qué tamaño. Los planes
     * usan la cota alta, para que nunca se queden cortos de camiones.
     */
    public static final double MEAN_FILL = 1.0;

    /** Los llenados que se miden: el plan se reporta con cada uno. */
    public static final List<Double> FILLS = List.of(0.5, 0.75, 1.0);

    /**
     * {@code PENDIENTE_VERIFICAR}: kilos que carga un compactador de cargue lateral de 32 yd³. Ningún operador publica
     * los kilos de los suyos; se toman los 8.000 kg que Torca declara para sus compactadores de 32 yd.
     */
    public static final int SIDE_LOADER_KG = 8_000;

    /**
     * Un turno de cargue lateral: es una instancia aparte, con los contenedores que el operador le asigna en su capa.
     *
     * @param name nombre corto para la instancia
     * @param operatorName como lo escribe el operador en su capa
     * @param start hora de inicio, {@code HH:mm}
     */
    public record Shift(String name, String operatorName, String start, int seconds) {
    }

    /**
     * Supuesto: viajes que encadena un camión de cargue lateral en su turno. Con 3, el máximo de F4, la flota del ASE 2
     * salía igual con 40 que con 90 min de descarga: en turnos de 10 h con viajes cortos limitaba el máximo, con el
     * turno a medio usar.
     */
    public static final int MAX_TRIPS_PER_TRUCK = 4;

    public record Planned(int count, List<Integer> liters) {

        public Planned {
            liters = List.copyOf(liters);
        }
    }

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
     * @param sideLoaders compactadores de cargue lateral que declara el operador; vacío si no los declara
     * @param shifts turnos en que se planifican sus contenedores
     * @param planned la cantidad oficial que se simula, si el operador no publica la ubicación de sus contenedores
     */
    public record Area(ServiceAreas.ServiceArea area, Optional<Double> tonnesPerDay, int collectionsPerWeek,
            boolean frequencyPublished, OptionalInt sideLoaders, List<Shift> shifts, Optional<Planned> planned) {

        public Area {
            shifts = List.copyOf(shifts);
            Objects.requireNonNull(planned, "planned");
        }

        /**
         * La operación con que se planifica un turno: no declara qué parte de las toneladas va por cargue lateral, y
         * cada camión encadena hasta {@link #MAX_TRIPS_PER_TRUCK} viajes.
         */
        public ServiceAreas.Operation operation(Shift shift) {
            if (!shifts.contains(shift)) {
                throw new IllegalArgumentException(shift.name() + " no es un turno de " + area.name());
            }
            return new ServiceAreas.Operation(OptionalDouble.empty(), collectionsPerWeek, shift.start(),
                    shift.seconds(), SIDE_LOADER_KG, List.of(), MAX_TRIPS_PER_TRUCK);
        }

        public List<Integer> frequencies() {
            return frequencyPublished || collectionsPerWeek == SENSITIVITY_COLLECTIONS_PER_WEEK
                    ? List.of(collectionsPerWeek)
                    : List.of(collectionsPerWeek, SENSITIVITY_COLLECTIONS_PER_WEEK);
        }

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
     *
     * Compactadores de cargue lateral: 3 en el ASE 1 y 5 de 32 yd³ en el ASE 2 (Superservicios). Turnos del ASE 2: su
     * capa asigna cada contenedor al de día o al de noche, de 06:00–16:00 y 18:30–04:30 según su informe.
     */
    public static final Shift ASE_2_DAY = new Shift("Dia", "DIA", "06:00", 10 * 3600);

    public static final Shift ASE_2_NIGHT = new Shift("Noche", "NOCHE", "18:30", 10 * 3600);

    /**
     * La capa del ASE 1 asigna cada contenedor al turno de día o al de noche. Las horas son las de sus microrrutas
     * domiciliarias de 3 veces por semana, 06:00–16:00 y 18:00–04:00 (capa de microrrutas del operador); no se ha
     * cruzado la microrruta de cada contenedor.
     */
    public static final Shift ASE_1_DAY = new Shift("Dia", "DIA", "06:00", 10 * 3600);

    public static final Shift ASE_1_NIGHT = new Shift("Noche", "NOCHE", "18:00", 10 * 3600);

    /**
     * {@code PENDIENTE_VERIFICAR}: la capa del ASE 4 no trae turno, así que todos sus contenedores van al de mañana,
     * 06:00–15:00. La Superservicios vio su compactador de carga lateral en la microrruta 411067 a las 10:45.
     */
    public static final Shift ASE_4_DAY = new Shift("Dia", "", "06:00", 9 * 3600);

    /**
     * {@code PENDIENTE_VERIFICAR}: los contenedores simulados del ASE 3 van al turno de 06:00–14:00, el que su informe
     * de la Superservicios pone al lado de la frecuencia de "atención de contenedores".
     */
    public static final Shift ASE_3_DAY = new Shift("Dia", "", "06:00", 8 * 3600);

    /**
     * El turno de 18:00 a 02:00 del ASE 5, el mismo de su {@link ServiceAreas.Operation}. Sus contenedores no tienen
     * ubicación publicada: todos se simulan en este turno.
     */
    public static final Shift ASE_5_NIGHT = new Shift("Noche", "", "18:00", 8 * 3600);

    /** Contenedores negros del Plan de Contenerización de 2026 del ASE 3: 91 de 1.100 L, sin ubicación publicada. */
    public static final int ASE_3_CONTAINERS = 91;

    public static final int ASE_3_LITERS = 1_100;

    /** Contenedores negros del plan de 2026 del ASE 5, de tamaños no publicados. */
    public static final int ASE_5_CONTAINERS = 320;

    public static final List<Area> AREAS = List.of(
            new Area(ServiceAreas.ASE_1, Optional.of(1_169.0), 3, true, OptionalInt.of(3),
                    List.of(ASE_1_DAY, ASE_1_NIGHT), Optional.empty()),
            new Area(ServiceAreas.ASE_2, Optional.of(1_804.7), 3, false, OptionalInt.of(5),
                    List.of(ASE_2_DAY, ASE_2_NIGHT), Optional.empty()),
            new Area(ServiceAreas.ASE_3, Optional.of(1_075.0), 6, true, OptionalInt.empty(), List.of(ASE_3_DAY),
                    Optional.of(new Planned(ASE_3_CONTAINERS, List.of(ASE_3_LITERS)))),
            new Area(ServiceAreas.ASE_4, Optional.of(725.1), 3, false, OptionalInt.empty(), List.of(ASE_4_DAY),
                    Optional.empty()),
            new Area(ServiceAreas.ASE_5, Optional.empty(), 6, true, OptionalInt.empty(), List.of(ASE_5_NIGHT),
                    Optional.of(new Planned(ASE_5_CONTAINERS, ServiceAreas.ASE_5.verified().containerLiters()))));

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

    public static double kgPerDay(List<Integer> liters, int collectionsPerWeek, double fill) {
        long kg = 0;
        for (int l : liters) {
            kg += kgAtCollection(l, fill);
        }
        return kg * collectionsPerWeek / 7.0;
    }
}
