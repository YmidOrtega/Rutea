package me.ymid.rutea.app.application;

import java.util.List;

import me.ymid.rutea.domain.WasteStream;

/**
 * Lo que transmiten los vehículos (R08), como lo recibe el mapa por WebSocket junto al reloj de la simulación.
 *
 * @param vehicles solo los que cambiaron desde el reporte anterior; en una instantánea, todos los que están en la
 *     calle
 */
public record TelemetryMessage(String type, Clock clock, List<Report> vehicles) {

    public TelemetryMessage(Clock clock, List<Report> vehicles) {
        this("telemetry", clock, List.copyOf(vehicles));
    }

    public record Clock(double seconds, double speed, boolean running, double endSeconds) {
    }

    /** @param points lo recorrido desde el reporte anterior, como [lon, lat, segundos]; el último es dónde está */
    public record Report(String id, WasteStream stream, VehicleState state, List<double[]> points) {
    }

    public enum VehicleState { DRIVING, COLLECTING, UNLOADING, BROKEN, DONE }
}
