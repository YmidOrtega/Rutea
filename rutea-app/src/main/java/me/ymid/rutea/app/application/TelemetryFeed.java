package me.ymid.rutea.app.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import me.ymid.rutea.app.application.TelemetryMessage.Report;
import me.ymid.rutea.app.application.TelemetryMessage.VehicleState;
import me.ymid.rutea.sim.SimulationClock;
import me.ymid.rutea.sim.Track;

/**
 * Arma los reportes de telemetría. Cada uno lleva lo recorrido desde el anterior, no solo el último punto: a 60× un
 * reporte cubre 15 s de calle, y la estela tiene que seguir las calles, no cortar las esquinas. Un vehículo quieto
 * que no cambió de estado no se reporta.
 */
final class TelemetryFeed {

    /** Lo que una instantánea manda de estela: los mismos 15 min que dibuja el mapa. */
    static final double TRAIL_SECONDS = 900;

    private final List<double[]> unloadSites;
    private final Map<String, VehicleState> lastState = new HashMap<>();
    private double lastSeconds = -1;

    /** @param unloadSites donde los vehículos descargan, [lon, lat]: una parada ahí es una descarga */
    TelemetryFeed(List<double[]> unloadSites) {
        this.unloadSites = List.copyOf(unloadSites);
    }

    /** Vacío si el reloj no avanzó desde el reporte anterior y no se pide igual. */
    Optional<TelemetryMessage> next(List<Vehicle> vehicles, SimulationClock.State clock, boolean always) {
        double now = clock.seconds();
        if (!always && now == lastSeconds) {
            return Optional.empty();
        }
        // El reloj volvió atrás: empezó un turno nuevo y nadie tiene nada que continuar.
        double from = now < lastSeconds ? 0 : lastSeconds;
        List<Report> reports = new ArrayList<>();
        for (Vehicle vehicle : vehicles) {
            report(vehicle, Math.max(from, vehicle.track().start()), now)
                    .filter(report -> report.points().size() > 1 || report.state() == VehicleState.DRIVING
                            || lastState.get(report.id()) != report.state())
                    .ifPresent(report -> {
                        reports.add(report);
                        lastState.put(report.id(), report.state());
                    });
        }
        Set<String> present = vehicles.stream().map(Vehicle::id).collect(Collectors.toSet());
        lastState.keySet().retainAll(present);
        lastSeconds = now;
        return Optional.of(new TelemetryMessage(clock(clock), reports));
    }

    TelemetryMessage snapshot(List<Vehicle> vehicles, SimulationClock.State clock) {
        List<Report> reports = new ArrayList<>();
        for (Vehicle vehicle : vehicles) {
            double from = Math.max(vehicle.track().start(), clock.seconds() - TRAIL_SECONDS);
            report(vehicle, from, clock.seconds()).ifPresent(reports::add);
        }
        return new TelemetryMessage(clock(clock), reports);
    }

    /** Vacío si el vehículo todavía no sale: una reserva antes de su despacho. */
    private Optional<Report> report(Vehicle vehicle, double from, double to) {
        Track track = vehicle.track();
        if (to < track.start()) {
            return Optional.empty();
        }
        double until = Math.min(to, vehicle.brokenAtSeconds());
        List<double[]> points = new ArrayList<>();
        if (from <= track.start()) {
            points.add(new double[] {track.lon()[0], track.lat()[0], track.start()});
        }
        for (double[] point : track.between(Math.min(from, until), until)) {
            points.add(point);
        }
        points.replaceAll(point -> new double[] {round(point[0], 1e5), round(point[1], 1e5), round(point[2], 10)});
        return Optional.of(new Report(vehicle.id(), vehicle.stream(), state(vehicle, until, to), points));
    }

    private VehicleState state(Vehicle vehicle, double until, double now) {
        Track track = vehicle.track();
        if (now >= vehicle.brokenAtSeconds()) {
            return VehicleState.BROKEN;
        }
        if (until >= track.end()) {
            return VehicleState.DONE;
        }
        if (!track.stopped(until)) {
            return VehicleState.DRIVING;
        }
        double[] here = track.at(until);
        for (double[] site : unloadSites) {
            if (site[0] == here[0] && site[1] == here[1]) {
                return VehicleState.UNLOADING;
            }
        }
        return VehicleState.COLLECTING;
    }

    private static TelemetryMessage.Clock clock(SimulationClock.State state) {
        return new TelemetryMessage.Clock(round(state.seconds(), 10), state.speed(), state.running(),
                state.endSeconds());
    }

    private static double round(double value, double scale) {
        return Math.round(value * scale) / scale;
    }
}
