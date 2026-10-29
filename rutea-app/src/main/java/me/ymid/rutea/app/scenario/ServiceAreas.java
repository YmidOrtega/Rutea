package me.ymid.rutea.app.scenario;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Las 20 localidades de Bogotá y las 5 Áreas de Servicio Exclusivo (ASE) que las atienden (ADR 0012). La
 * localidad es lo que se elige; la ASE, quién recoge. Cada dato está en {@code docs/datos.md}; si cambia uno,
 * se actualizan los dos en el mismo cambio.
 *
 * <p>Reparto y operadores: "Así está dividido el esquema de aseo en las localidades de Bogotá" (Bogotá.gov.co,
 * 7 de febrero de 2026). Relaciones de OSM: {@code admin_level=8}, en el extracto de {@code data/osm}; su
 * etiqueta {@code ref} es el número oficial de la localidad. Suelo urbano por ASE: "Línea base Plan de Gestión
 * Integral de Residuos Sólidos Bogotá D.C., PGIRS", agosto de 2018, Tabla 3, parámetro 1 (UAESP, en el
 * Observatorio del Hábitat): 24, 31, 18, 12 y 15 % de 37.969 ha.
 */
public final class ServiceAreas {

    /**
     * @param number número oficial, de 1 a 20
     * @param rural toda la localidad es rural: su modelo de recolección es {@code PENDIENTE_VERIFICAR}
     */
    public record Locality(int number, String name, long osmRelation, boolean rural) {

        public Locality {
            Objects.requireNonNull(name, "name");
            if (number < 1 || number > 20) {
                throw new IllegalArgumentException("localidad " + number + " fuera de 1–20");
            }
        }

        /** El nombre en minúsculas, sin tildes y con guiones: "La Candelaria" da {@code la-candelaria}. */
        public String slug() {
            return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                    .toLowerCase(Locale.ROOT).replace(' ', '-');
        }
    }

    /**
     * @param locality localidad donde queda la base, que no tiene que ser de la ASE
     * @param osmNode nodo de OSM sobre la vía, donde el grafo ubica la base
     */
    public record Base(String address, String locality, long osmNode) {

        public Base {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(locality, "locality");
        }
    }

    /**
     * Lo que declara el operador de una ASE, según su inspección de la Superservicios.
     *
     * @param sideLoadingShare parte de las toneladas domiciliarias que se recoge por cargue lateral, es decir, con
     *     contenedores; vacía si el operador no la declara
     * @param collectionsPerWeek veces por semana que se recoge
     * @param shiftStart inicio del turno que se planifica, {@code HH:mm}
     * @param compactorKg capacidad del compactador más común de la flota
     * @param containerLiters tamaños de los contenedores de no aprovechables en vía pública
     * @param maxTripsPerTruck supuesto: viajes que puede encadenar un camión en su turno
     */
    public record Operation(OptionalDouble sideLoadingShare, int collectionsPerWeek, String shiftStart, int shiftSeconds,
            int compactorKg, List<Integer> containerLiters, int maxTripsPerTruck) {

        public Operation {
            Objects.requireNonNull(sideLoadingShare, "sideLoadingShare");
            Objects.requireNonNull(shiftStart, "shiftStart");
            containerLiters = List.copyOf(containerLiters);
        }
    }

    /**
     * @param urbanAreaShare parte del suelo urbano de Bogotá que cubre la ASE ("cobertura de recolección área
     *     urbana" del PGIRS). Es la única participación por ASE publicada; Rutea la usa como aproximación de la parte
     *     de las toneladas y de los contenedores de la ciudad, un supuesto
     * @param base base de operaciones del operador, según su informe de la Superservicios
     * @param operation vacía mientras las cifras del operador sean {@code PENDIENTE_VERIFICAR}
     */
    public record ServiceArea(int number, String operator, List<Locality> localities, double urbanAreaShare,
            Base base, Optional<Operation> operation) {

        public ServiceArea {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(operation, "operation");
            localities = List.copyOf(localities);
            if (localities.isEmpty()) {
                throw new IllegalArgumentException("la ASE " + number + " no tiene localidades");
            }
        }

        /** La localidad, si la ASE tiene una sola; si no, "ASE n". */
        public String name() {
            return localities.size() == 1 ? localities.getFirst().name() : "ASE " + number;
        }

        /**
         * Supuesto: contenedores negros, los de la ciudad × la parte del suelo urbano × 1/2. No hay inventario por ASE
         * publicado.
         */
        public int nonRecyclableContainers() {
            return (int) Math.round(BogotaFigures.BOGOTA_CONTAINERS * urbanAreaShare
                    * BogotaFigures.NON_RECYCLABLE_CONTAINER_SHARE);
        }

        /** Contenedores blancos: la otra mitad, "la mitad para promover el reciclaje" (UAESP, 2025). */
        public int recyclableContainers() {
            return (int) Math.round(BogotaFigures.BOGOTA_CONTAINERS * urbanAreaShare
                    * (1 - BogotaFigures.NON_RECYCLABLE_CONTAINER_SHARE));
        }

        /**
         * Supuesto: kilos por día que pasan por los contenedores negros, las toneladas de la ciudad × la parte del
         * suelo urbano × el cargue lateral del operador.
         */
        public long containerKgPerDay() {
            return Math.round(BogotaFigures.BOGOTA_ORDINARY_TONNES_PER_DAY * 1000.0 * urbanAreaShare
                    * verified().sideLoadingShare().orElseThrow(() -> new IllegalStateException("el operador de la"
                            + " ASE " + number + " no declara qué parte recoge por cargue lateral")));
        }

        public double meanKgPerCollection(int containers) {
            return containerKgPerDay() * 7.0 / verified().collectionsPerWeek() / containers;
        }

        /**
         * Supuesto: kilos de aprovechables por día en los contenedores blancos, con la misma proporción que en la
         * ciudad: lo aprovechado frente a lo dispuesto, sobre lo que pasa por los negros.
         */
        public long recyclableKgPerDay() {
            return Math.round(containerKgPerDay() * BogotaFigures.RECOVERED_SHARE
                    / (1 - BogotaFigures.RECOVERED_SHARE));
        }

        public double meanRecyclableKgPerCollection(int containers) {
            return recyclableKgPerDay() * 7.0 / verified().collectionsPerWeek() / containers;
        }

        /** @throws IllegalStateException si la operación del operador sigue {@code PENDIENTE_VERIFICAR} */
        public Operation verified() {
            return operation.orElseThrow(() -> new IllegalStateException("la ASE " + number + " no tiene las cifras"
                    + " de operación del operador: están PENDIENTE_VERIFICAR en docs/datos.md"));
        }
    }

    /**
     * Portería noreste del Relleno Sanitario Doña Juana, donde descargan los compactadores de todas las ASE:
     * nodo {@code barrier=swing_gate} sobre el borde del polígono del relleno, del lado de la ciudad.
     */
    public static final long DISPOSAL_OSM_NODE = 1013564592;

    /** Promoambiental Distrito S.A.S. E.S.P. Base: Carrera 60 # 15-07, Puente Aranda. */
    public static final ServiceArea ASE_1 = new ServiceArea(1, "Promoambiental Distrito", List.of(
            new Locality(1, "Usaquén", 2214885, false),
            new Locality(2, "Chapinero", 1379547, false),
            new Locality(3, "Santa Fe", 4740232, false),
            new Locality(4, "San Cristóbal", 4740231, false),
            new Locality(5, "Usme", 4740233, false),
            new Locality(17, "La Candelaria", 2218276, false),
            new Locality(20, "Sumapaz", 5579632, true)),
            0.24, new Base("Carrera 60 # 15-07", "Puente Aranda", 8483428972L), Optional.empty());

    /** Limpieza Metropolitana S.A. E.S.P. (LIME). Base: Carrera 62 # 19-04 Int. 4, Puente Aranda. */
    public static final ServiceArea ASE_2 = new ServiceArea(2, "LIME", List.of(
            new Locality(6, "Tunjuelito", 2216689, false),
            new Locality(7, "Bosa", 4740227, false),
            new Locality(13, "Teusaquillo", 2216632, false),
            new Locality(14, "Los Mártires", 2216631, false),
            new Locality(15, "Antonio Nariño", 2216654, false),
            new Locality(16, "Puente Aranda", 2216655, false),
            new Locality(18, "Rafael Uribe Uribe", 4740230, false),
            new Locality(19, "Ciudad Bolívar", 4740228, false)),
            0.31, new Base("Carrera 62 # 19-04 Int. 4", "Puente Aranda", 3868073372L), Optional.empty());

    /** Ciudad Limpia Bogotá S.A. E.S.P. Base: Calle 59C Sur # 51-50, Ciudad Bolívar, fuera de su ASE. */
    public static final ServiceArea ASE_3 = new ServiceArea(3, "Ciudad Limpia", List.of(
            new Locality(8, "Kennedy", 4740229, false),
            new Locality(9, "Fontibón", 2216630, false)),
            0.18, new Base("Calle 59C Sur # 51-50", "Ciudad Bolívar", 6580796244L), Optional.empty());

    /** Bogotá Limpia S.A.S. E.S.P. Base: Calle 63 # 96-05, Engativá. */
    public static final ServiceArea ASE_4 = new ServiceArea(4, "Bogotá Limpia", List.of(
            new Locality(10, "Engativá", 2214883, false),
            new Locality(12, "Barrios Unidos", 2214882, false)),
            0.12, new Base("Calle 63 # 96-05", "Engativá", 5769034872L), Optional.empty());

    /**
     * Área Limpia Distrito Capital S.A.S. E.S.P., según la inspección de la Superservicios del 9 al 13 de marzo de
     * 2026, que compara el PGIRS con el PPSA del operador:
     *
     * <ul>
     *   <li>Base: Avenida Calle 17 # 124-81, Fontibón. El nodo es la esquina de la Av. Calle 17 con la Carrera 124,
     *       a unos 81 m de ella.
     *   <li>Cargue lateral: al menos el 13 % de las toneladas domiciliarias (PPSA); se usa el mínimo.
     *   <li>Frecuencia: el operador declara de 3 a 6 veces por semana. Se toma 6, de lunes a sábado, lo que da una
     *       carga típica de 7/6 días de generación por recolección.
     *   <li>Turnos de 8 h; se planifica el de 18:00 a 02:00, uno de sus dos turnos principales.
     *   <li>Compactador más común: 17 de 32 yardas, 8 toneladas.
     *   <li>Contenedores de no aprovechables en vía pública: de 3.200, 2.400 y 1.100 L.
     *   <li>Supuesto de F4: hasta 3 viajes por camión en el turno. Con viajes largos hasta Doña Juana, el turno limita
     *       antes.
     * </ul>
     */
    public static final ServiceArea ASE_5 = new ServiceArea(5, "Área Limpia", List.of(
            new Locality(11, "Suba", 2214884, false)),
            0.15, new Base("Avenida Calle 17 # 124-81", "Fontibón", 865490722),
            Optional.of(new Operation(OptionalDouble.of(0.13), 6, "18:00", 8 * 3600, 8_000, List.of(3_200, 2_400, 1_100), 3)));

    public static final List<ServiceArea> BOGOTA = List.of(ASE_1, ASE_2, ASE_3, ASE_4, ASE_5);

    private ServiceAreas() {
    }

    public static Optional<Locality> locality(int number) {
        for (ServiceArea area : BOGOTA) {
            for (Locality locality : area.localities()) {
                if (locality.number() == number) {
                    return Optional.of(locality);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Reparte una selección de localidades entre las ASE que las atienden: cada entrada es una instancia
     * independiente. Las ASE y sus localidades salen en el orden del catálogo, sin importar el de la selección.
     *
     * @throws IllegalArgumentException si la selección está vacía o trae una localidad que no es del catálogo
     */
    public static Map<ServiceArea, List<Locality>> split(Collection<Locality> selection) {
        if (selection.isEmpty()) {
            throw new IllegalArgumentException("no hay localidades elegidas");
        }
        for (Locality locality : selection) {
            if (locality(locality.number()).filter(locality::equals).isEmpty()) {
                throw new IllegalArgumentException("la localidad " + locality.name() + " no es del catálogo");
            }
        }
        Map<ServiceArea, List<Locality>> split = new LinkedHashMap<>();
        for (ServiceArea area : BOGOTA) {
            List<Locality> chosen = new ArrayList<>();
            for (Locality locality : area.localities()) {
                if (selection.contains(locality)) {
                    chosen.add(locality);
                }
            }
            if (!chosen.isEmpty()) {
                split.put(area, List.copyOf(chosen));
            }
        }
        return split;
    }
}
