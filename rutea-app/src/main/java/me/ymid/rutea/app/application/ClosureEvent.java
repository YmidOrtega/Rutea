package me.ymid.rutea.app.application;

import java.util.List;
import java.util.Map;

import me.ymid.rutea.app.planning.DemoPlan;

/**
 * Una vía cerrada y lo que cambió en la operación. Las horas son segundos desde el inicio del turno.
 *
 * @param path geometría de la calle cerrada, [lon, lat]
 * @param latencyMs reparar la matriz y replanificar, sin dibujar las rutas
 * @param recomputed filas más columnas de la matriz que se recalcularon, de {@code matrixSize}
 * @param dropped contenedores aislados que tenían una visita pendiente
 * @param visits hora nueva de llegada a cada contenedor del plan de los camiones que cambiaron
 * @param citation R05: un cambio de ruta por caso fortuito no espera el aviso de tres días
 */
public record ClosureEvent(int seq, int area, long atSeconds, String roadClass, List<double[]> path, double latencyMs,
        int recomputed, int matrixSize, int changedPairs, List<String> isolated, List<String> dropped,
        List<Change> changes, List<DemoPlan.Route> routes, Map<String, Long> visits, String citation)
        implements OperationEvent {

    public record Change(String truck, long extraMeters, long extraSeconds, boolean rerouted, boolean late) {
    }

    public ClosureEvent {
        path = List.copyOf(path);
        isolated = List.copyOf(isolated);
        dropped = List.copyOf(dropped);
        changes = List.copyOf(changes);
        routes = List.copyOf(routes);
        visits = Map.copyOf(visits);
    }
}
