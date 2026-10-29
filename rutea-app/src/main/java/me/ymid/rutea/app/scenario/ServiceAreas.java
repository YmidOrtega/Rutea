package me.ymid.rutea.app.scenario;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Las 20 localidades de Bogotá y las 5 Áreas de Servicio Exclusivo (ASE) que las atienden (ADR 0012). La
 * localidad es lo que se elige; la ASE, quién recoge. Cada dato está en {@code docs/datos.md}; si cambia uno,
 * se actualizan los dos en el mismo cambio.
 *
 * <p>Reparto y operadores: "Así está dividido el esquema de aseo en las localidades de Bogotá" (Bogotá.gov.co,
 * 7 de febrero de 2026). Relaciones de OSM: {@code admin_level=8}, en el extracto de {@code data/osm}; su
 * etiqueta {@code ref} es el número oficial de la localidad.
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
    }

    /** @param osmNode nodo de OSM sobre la vía, donde el grafo ubica la base */
    public record Base(String address, long osmNode) {

        public Base {
            Objects.requireNonNull(address, "address");
        }
    }

    /** @param base vacía mientras su ubicación sea {@code PENDIENTE_VERIFICAR} */
    public record ServiceArea(int number, String operator, List<Locality> localities, Optional<Base> base) {

        public ServiceArea {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(base, "base");
            localities = List.copyOf(localities);
            if (localities.isEmpty()) {
                throw new IllegalArgumentException("la ASE " + number + " no tiene localidades");
            }
        }
    }

    /** Promoambiental Distrito S.A.S. E.S.P. */
    public static final ServiceArea ASE_1 = new ServiceArea(1, "Promoambiental Distrito", List.of(
            new Locality(1, "Usaquén", 2214885, false),
            new Locality(2, "Chapinero", 1379547, false),
            new Locality(3, "Santa Fe", 4740232, false),
            new Locality(4, "San Cristóbal", 4740231, false),
            new Locality(5, "Usme", 4740233, false),
            new Locality(17, "La Candelaria", 2218276, false),
            new Locality(20, "Sumapaz", 5579632, true)),
            Optional.empty());

    /** Limpieza Metropolitana S.A. E.S.P. (LIME). */
    public static final ServiceArea ASE_2 = new ServiceArea(2, "LIME", List.of(
            new Locality(6, "Tunjuelito", 2216689, false),
            new Locality(7, "Bosa", 4740227, false),
            new Locality(13, "Teusaquillo", 2216632, false),
            new Locality(14, "Los Mártires", 2216631, false),
            new Locality(15, "Antonio Nariño", 2216654, false),
            new Locality(16, "Puente Aranda", 2216655, false),
            new Locality(18, "Rafael Uribe Uribe", 4740230, false),
            new Locality(19, "Ciudad Bolívar", 4740228, false)),
            Optional.empty());

    /** Ciudad Limpia Bogotá S.A. E.S.P. */
    public static final ServiceArea ASE_3 = new ServiceArea(3, "Ciudad Limpia", List.of(
            new Locality(8, "Kennedy", 4740229, false),
            new Locality(9, "Fontibón", 2216630, false)),
            Optional.empty());

    /** Bogotá Limpia S.A.S. E.S.P. */
    public static final ServiceArea ASE_4 = new ServiceArea(4, "Bogotá Limpia", List.of(
            new Locality(10, "Engativá", 2214883, false),
            new Locality(12, "Barrios Unidos", 2214882, false)),
            Optional.empty());

    /**
     * Área Limpia Distrito Capital S.A.S. E.S.P. Base según la inspección de la Superservicios de marzo de 2026;
     * el nodo es la esquina de la Av. Calle 17 con la Carrera 124, a unos 81 m de ella.
     */
    public static final ServiceArea ASE_5 = new ServiceArea(5, "Área Limpia", List.of(
            new Locality(11, "Suba", 2214884, false)),
            Optional.of(new Base("Avenida Calle 17 # 124-81, Fontibón", 865490722)));

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
