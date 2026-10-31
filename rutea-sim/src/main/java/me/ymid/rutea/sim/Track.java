package me.ymid.rutea.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * El recorrido de un vehículo con la hora de paso por cada punto, en segundos desde el inicio del turno. Un punto
 * repetido es una parada: el vehículo llega a una hora y arranca a la siguiente.
 */
public record Track(double[] lon, double[] lat, double[] times) {

    public Track {
        if (lon.length == 0 || lat.length != lon.length || times.length != lon.length) {
            throw new IllegalArgumentException("un recorrido necesita al menos un punto, con su hora");
        }
        for (int i = 1; i < times.length; i++) {
            if (times[i] < times[i - 1]) {
                throw new IllegalArgumentException("la hora retrocede en el punto " + i);
            }
        }
    }

    public double start() {
        return times[0];
    }

    public double end() {
        return times[times.length - 1];
    }

    /** {lon, lat}; antes de salir, en el primer punto, y después de llegar, en el último. */
    public double[] at(double seconds) {
        int i = segment(seconds);
        if (i < 0) {
            return new double[] {lon[0], lat[0]};
        }
        if (i == times.length - 1) {
            return new double[] {lon[i], lat[i]};
        }
        double span = times[i + 1] - times[i];
        double f = span == 0 ? 1 : (seconds - times[i]) / span;
        return new double[] {lon[i] + f * (lon[i + 1] - lon[i]), lat[i] + f * (lat[i + 1] - lat[i])};
    }

    public boolean stopped(double seconds) {
        int i = segment(seconds);
        return i < 0 || i == times.length - 1 || lon[i] == lon[i + 1] && lat[i] == lat[i + 1];
    }

    /**
     * Lo recorrido en {@code (from, to]}, como {lon, lat, segundos}: los puntos de la geometría por los que pasa y la
     * posición a {@code to}. Así una estela sigue las calles aunque los reportes lleguen espaciados.
     */
    public List<double[]> between(double from, double to) {
        List<double[]> points = new ArrayList<>();
        for (int i = Math.max(0, segment(from) + 1); i < times.length && times[i] < to; i++) {
            points.add(new double[] {lon[i], lat[i], times[i]});
        }
        double[] here = at(to);
        points.add(new double[] {here[0], here[1], to});
        return points;
    }

    private int segment(double seconds) {
        int lo = 0;
        int hi = times.length - 1;
        if (seconds < times[0]) {
            return -1;
        }
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (times[mid] <= seconds) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }
}
