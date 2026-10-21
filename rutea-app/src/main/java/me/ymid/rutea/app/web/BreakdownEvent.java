package me.ymid.rutea.app.web;

import java.util.List;

import me.ymid.rutea.app.planning.DemoPlan;

/**
 * Una avería y su replanificación, como la reciben todos los clientes (R05, R08). Las horas son segundos desde
 * el inicio del turno; los tiempos de las rutas de reserva ya vienen corridos a la hora de despacho.
 *
 * @param orphans ids de los contenedores que el camión averiado no alcanzó a recoger
 * @param reserve rutas de los camiones de reserva (R-01, R-02…)
 * @param latencyMs tiempo real de replanificar
 * @param meetsRecoveryRule si la reserva llega al primer huérfano en máximo tres horas (R05)
 */
public record BreakdownEvent(String truck, long atSeconds, long dispatchSeconds, double latencyMs,
        List<String> orphans, List<DemoPlan.Route> reserve, long firstPickupSeconds, long lastPickupSeconds,
        boolean meetsRecoveryRule, String citation) {

    public BreakdownEvent {
        orphans = List.copyOf(orphans);
        reserve = List.copyOf(reserve);
    }
}
