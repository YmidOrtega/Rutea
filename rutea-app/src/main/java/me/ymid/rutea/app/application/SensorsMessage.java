package me.ymid.rutea.app.application;

import java.util.Map;

/**
 * Lecturas de los sensores de llenado, en porcentaje y en pasos de {@link LiveSession#SENSOR_STEP_PERCENT}: solo las
 * que cambiaron. Una instantánea trae todas y los litros de cada contenedor.
 */
public record SensorsMessage(String type, double seconds, Map<String, Integer> readings, Map<String, Integer> liters) {

    public SensorsMessage(double seconds, Map<String, Integer> readings, Map<String, Integer> liters) {
        this("sensors", seconds, Map.copyOf(readings), Map.copyOf(liters));
    }
}
