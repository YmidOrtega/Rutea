package me.ymid.rutea.app.planning;

import java.util.List;

import me.ymid.rutea.domain.WasteStream;

/**
 * Plan que dibuja el frontend. Las posiciones van como [lon, lat], el orden que esperan MapLibre y deck.gl.
 * Las calles son reales (OSM); los contenedores y sus cargas son simulados.
 */
public record DemoPlan(Meta meta, List<Site> sites, List<Container> containers, List<Route> routes) {

    /**
     * @param network de dónde salen las calles
     * @param shiftStart hora a la que los camiones salen de la base, "HH:mm"
     * @param serviceSeconds segundos detenido en cada contenedor (supuesto, ver SubaFigures)
     * @param attribution atribución obligatoria de los datos de OSM (ODbL)
     */
    public record Meta(String zone, boolean simulated, String network, String solver, long seed, long iterations,
            String shiftStart, int serviceSeconds, String attribution) {
    }

    /** Lugar fijo de la operación: la base de los camiones o el sitio de disposición. */
    public record Site(String id, String label, double[] position) {
    }

    /** Sin estado de llenado: no hay sensores hasta F5, y un porcentaje inventado no sería honesto. */
    public record Container(String id, WasteStream stream, double[] position, int kg, String route) {
    }

    /**
     * El turno de un compactador: sale de la base, recoge, descarga en el relleno y vuelve a la base.
     * {@code times[i]} son los segundos desde la salida hasta {@code path[i]}, con las velocidades del grafo y
     * las paradas en los contenedores; {@code distanceMeters} incluye el regreso a la base.
     */
    public record Route(String id, WasteStream stream, int capacityKg, int loadKg, long distanceMeters,
            int durationSeconds, List<String> stops, List<double[]> path, int[] times) {
    }
}
