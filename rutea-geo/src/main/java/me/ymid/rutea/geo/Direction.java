package me.ymid.rutea.geo;

import java.util.Map;

/** Sentidos de circulación de una vía de OSM, respecto al orden de sus nodos. */
enum Direction {

    BOTH, FORWARD, BACKWARD,
    /** Sentido que cambia según la hora ({@code oneway=reversible}): no se usa para planificar. */
    NONE;

    boolean forward() {
        return this == BOTH || this == FORWARD;
    }

    boolean backward() {
        return this == BOTH || this == BACKWARD;
    }

    static Direction of(Map<String, String> tags) {
        String oneway = tags.get("oneway");
        if (oneway != null) {
            switch (oneway) {
                case "yes", "true", "1" -> {
                    return FORWARD;
                }
                case "-1", "reverse" -> {
                    return BACKWARD;
                }
                case "no", "false", "0" -> {
                    return BOTH;
                }
                case "reversible", "alternating" -> {
                    return NONE;
                }
                default -> {
                    // Valor desconocido: se sigue con lo que implican las demás etiquetas.
                }
            }
        }
        String junction = tags.get("junction");
        if ("roundabout".equals(junction) || "circular".equals(junction) || "motorway".equals(tags.get("highway"))) {
            return FORWARD;
        }
        return BOTH;
    }
}
