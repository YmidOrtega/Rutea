package me.ymid.rutea.app.web;

import java.util.List;
import java.util.Map;

import me.ymid.rutea.app.operations.LiveOperation;
import me.ymid.rutea.app.planning.DemoPlan;

/**
 * Un contenedor desbordado y quién lo recoge. Las horas son segundos desde el inicio del turno.
 *
 * @param cause "reporte" si llegó desde el mapa; "avería de C-03" si el camión que lo iba a recoger se averió
 * @param truck el compactador que lo recoge o la reserva que sale por él (R-N0123)
 * @param extraMeters metros que se agregan a la operación; 0 si lo recoge su visita del plan
 * @param latencyMs tiempo real de decidir quién lo recoge, sin dibujar la ruta
 * @param routes el turno nuevo del compactador o el de la reserva; vacío si no cambia nada
 * @param visits hora a la que el compactador llega ahora a cada contenedor de su plan, corrida por la recogida
 *     extra; vacío si no cambia nada
 */
public record OverflowEvent(int seq, String container, long atSeconds, String cause, LiveOperation.Pickup pickup,
        String truck, long pickupSeconds, long extraMeters, double latencyMs, List<DemoPlan.Route> routes,
        Map<String, Long> visits) implements OperationEvent {

    public OverflowEvent {
        routes = List.copyOf(routes);
        visits = Map.copyOf(visits);
    }
}
