package me.ymid.rutea.app.planning;

import java.util.List;

import me.ymid.rutea.domain.WasteStream;

/**
 * Plan que dibuja el frontend. Las posiciones van como [lon, lat], el orden que esperan MapLibre y deck.gl.
 * Las calles son reales (OSM); los contenedores y sus cargas son simulados.
 */
public record DemoPlan(Meta meta, List<Site> sites, List<Container> containers, List<Route> routes,
        List<Compliance.Check> compliance) {

    /**
     * @param network de dónde salen las calles
     * @param shiftStart hora a la que los camiones salen de la base, "HH:mm"
     * @param shiftSeconds duración máxima del turno
     * @param serviceSeconds segundos detenido en cada contenedor (supuesto, ver SubaFigures)
     * @param unloadSeconds segundos de cada descarga en el relleno (supuesto, ver SubaFigures)
     * @param litersPerKm consumo supuesto de diésel (ver SubaFigures)
     * @param kgCo2PerLiter factor del IPCC 2006 para el diésel (ver DieselEmissions)
     * @param attribution atribución obligatoria de los datos de OSM (ODbL)
     * @param assumptions lo que el plan supone sin dato publicado, en texto para mostrarlo tal cual
     */
    public record Meta(String zone, boolean simulated, String network, String solver, long seed, long iterations,
            String shiftStart, int shiftSeconds, int serviceSeconds, int unloadSeconds, double litersPerKm,
            double kgCo2PerLiter, String attribution, List<String> assumptions) {
    }

    /** Lugar fijo de la operación: la base de los camiones o el sitio de disposición. */
    public record Site(String id, String label, double[] position) {
    }

    /**
     * Sin estado de llenado: no hay sensores hasta F5, y un porcentaje inventado no sería honesto.
     *
     * @param visitSeconds segundos desde el inicio del turno hasta que el camión llega
     * @param rules lo que la norma exige en esta parada (R02, R07), en texto
     */
    public record Container(String id, WasteStream stream, double[] position, int kg, String route, int visitSeconds,
            boolean noCompaction, boolean nightNoise, List<String> rules) {
    }

    /**
     * El turno de un compactador: sale de la base, hace uno o varios viajes con su descarga en el relleno y
     * vuelve a la base. {@code tripLoadsKg[i]} es la carga del viaje {@code i}, que nunca pasa de
     * {@code capacityKg}. {@code times[i]} son los segundos desde la salida hasta {@code path[i]}, con las
     * velocidades del grafo, las paradas en los contenedores y las descargas; {@code distanceMeters} incluye el
     * regreso a la base.
     */
    public record Route(String id, WasteStream stream, int capacityKg, int loadKg, long distanceMeters,
            int durationSeconds, int[] tripLoadsKg, List<String> stops, List<double[]> path, int[] times) {
    }
}
