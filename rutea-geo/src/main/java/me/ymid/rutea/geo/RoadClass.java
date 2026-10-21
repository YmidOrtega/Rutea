package me.ymid.rutea.geo;

import java.util.Map;
import java.util.Optional;

/**
 * Tipos de vía de OSM ({@code highway=*}) por los que puede circular un camión de recolección.
 *
 * <p>Las velocidades son una suposición para un compactador cargado en turno nocturno, no una
 * medición: se reemplazan cuando haya datos GPS de la operación. Si la vía tiene {@code maxspeed}
 * menor, manda ese límite.
 */
public enum RoadClass {

    MOTORWAY(60),
    TRUNK(50),
    PRIMARY(40),
    SECONDARY(35),
    TERTIARY(30),
    UNCLASSIFIED(25),
    RESIDENTIAL(20),
    LIVING_STREET(10),
    SERVICE(15),
    TRACK(10);

    private final int kmPerHour;

    RoadClass(int kmPerHour) {
        this.kmPerHour = kmPerHour;
    }

    public int kmPerHour() {
        return kmPerHour;
    }

    /** Vías principales: las únicas que se toman fuera de la localidad, para llegar al relleno. */
    public boolean isArterial() {
        return compareTo(SECONDARY) <= 0;
    }

    /** Vías en las que puede haber contenedores dentro de la localidad. */
    public boolean isPublicStreet() {
        return compareTo(LIVING_STREET) <= 0;
    }

    /**
     * Clase de la vía, o vacío si no es para vehículos ({@code footway}, {@code cycleway}), no está
     * construida, prohíbe el paso a vehículos de carga o es privada.
     */
    public static Optional<RoadClass> of(Map<String, String> tags) {
        return isPrivate(tags) ? Optional.empty() : ofAnyAccess(tags);
    }

    /**
     * Como {@link #of}, pero admite vías privadas: las de un relleno o una base son privadas para el
     * público, no para los camiones que van allí.
     */
    public static Optional<RoadClass> ofAnyAccess(Map<String, String> tags) {
        String highway = tags.get("highway");
        if (highway == null || "yes".equals(tags.get("area"))) {
            return Optional.empty();
        }
        RoadClass roadClass = switch (highway) {
            case "motorway", "motorway_link" -> MOTORWAY;
            case "trunk", "trunk_link" -> TRUNK;
            case "primary", "primary_link" -> PRIMARY;
            case "secondary", "secondary_link" -> SECONDARY;
            case "tertiary", "tertiary_link" -> TERTIARY;
            case "unclassified", "road" -> UNCLASSIFIED;
            case "residential" -> RESIDENTIAL;
            case "living_street" -> LIVING_STREET;
            case "service" -> SERVICE;
            case "track" -> TRACK;
            default -> null;
        };
        if (roadClass == null || forbidsTrucks(tags)) {
            return Optional.empty();
        }
        return Optional.of(roadClass);
    }

    private static final String[] ACCESS_KEYS = {"access", "vehicle", "motor_vehicle", "hgv"};

    private static boolean forbidsTrucks(Map<String, String> tags) {
        for (String key : ACCESS_KEYS) {
            if ("no".equals(tags.get(key))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPrivate(Map<String, String> tags) {
        for (String key : ACCESS_KEYS) {
            if ("private".equals(tags.get(key))) {
                return true;
            }
        }
        return false;
    }

    /** Velocidad para la vía: la de su clase o {@code maxspeed} si es menor. */
    public int kmPerHour(Map<String, String> tags) {
        String maxspeed = tags.get("maxspeed");
        if (maxspeed == null) {
            return kmPerHour;
        }
        int digits = 0;
        while (digits < maxspeed.length() && Character.isDigit(maxspeed.charAt(digits))) {
            digits++;
        }
        if (digits == 0 || digits > 3) {
            return kmPerHour;
        }
        int limit = Integer.parseInt(maxspeed, 0, digits, 10);
        if (maxspeed.substring(digits).strip().equals("mph")) {
            limit = (int) Math.round(limit * 1.609344);
        }
        return limit > 0 ? Math.min(kmPerHour, limit) : kmPerHour;
    }
}
