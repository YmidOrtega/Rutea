package me.ymid.rutea.geo.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Multipolígono con huecos, en 1e-7 grados. Usa la regla par-impar sobre todos los anillos, así que
 * no necesita saber cuáles son exteriores y cuáles interiores.
 */
public final class Polygon {

    private final int[][] ringLat;
    private final int[][] ringLon;
    private final BoundingBox bounds;

    /** Cada anillo es una secuencia cerrada: el último punto puede repetir el primero o no. */
    public Polygon(List<int[]> ringsLatE7, List<int[]> ringsLonE7) {
        if (ringsLatE7.isEmpty() || ringsLatE7.size() != ringsLonE7.size()) {
            throw new IllegalArgumentException("se esperaban anillos con latitudes y longitudes pareadas");
        }
        ringLat = ringsLatE7.toArray(int[][]::new);
        ringLon = ringsLonE7.toArray(int[][]::new);
        BoundingBox box = null;
        for (int r = 0; r < ringLat.length; r++) {
            if (ringLat[r].length < 3 || ringLat[r].length != ringLon[r].length) {
                throw new IllegalArgumentException("anillo " + r + " con " + ringLat[r].length + " puntos");
            }
            BoundingBox ringBox = BoundingBox.of(ringLat[r], ringLon[r]);
            box = box == null ? ringBox : box.union(ringBox);
        }
        bounds = box;
    }

    public BoundingBox bounds() {
        return bounds;
    }

    public int ringCount() {
        return ringLat.length;
    }

    public boolean contains(int latE7, int lonE7) {
        if (!bounds.contains(latE7, lonE7)) {
            return false;
        }
        boolean inside = false;
        for (int r = 0; r < ringLat.length; r++) {
            int[] lat = ringLat[r];
            int[] lon = ringLon[r];
            for (int i = 0, j = lat.length - 1; i < lat.length; j = i++) {
                if ((lat[i] > latE7) != (lat[j] > latE7)) {
                    double crossLon = lon[i] + (double) (latE7 - lat[i]) * (lon[j] - lon[i]) / (lat[j] - lat[i]);
                    if (lonE7 < crossLon) {
                        inside = !inside;
                    }
                }
            }
        }
        return inside;
    }

    /** Une las vías de un límite de OSM en anillos cerrados de ids de nodo. */
    public static List<long[]> assembleRings(List<long[]> ways) {
        List<long[]> open = new ArrayList<>();
        List<long[]> rings = new ArrayList<>();
        for (long[] way : ways) {
            if (way.length < 2) {
                continue;
            }
            if (way[0] == way[way.length - 1]) {
                rings.add(way);
            } else {
                open.add(way);
            }
        }
        while (!open.isEmpty()) {
            long[] ring = open.removeLast();
            boolean grew = true;
            while (ring[0] != ring[ring.length - 1] && grew) {
                grew = false;
                for (int i = 0; i < open.size(); i++) {
                    long[] next = open.get(i);
                    long[] joined = join(ring, next);
                    if (joined != null) {
                        ring = joined;
                        open.remove(i);
                        grew = true;
                        break;
                    }
                }
            }
            if (ring[0] != ring[ring.length - 1]) {
                throw new IllegalArgumentException("el límite tiene un anillo abierto entre los nodos "
                        + ring[0] + " y " + ring[ring.length - 1]);
            }
            rings.add(ring);
        }
        return rings;
    }

    /** Pega {@code b} al final de {@code a}, girándolo si hace falta; null si no comparten extremo. */
    private static long[] join(long[] a, long[] b) {
        long end = a[a.length - 1];
        if (b[0] == end) {
            return concat(a, b, false);
        }
        if (b[b.length - 1] == end) {
            return concat(a, b, true);
        }
        long start = a[0];
        if (b[b.length - 1] == start) {
            return concat(b, a, false);
        }
        if (b[0] == start) {
            return concat(reversed(b), a, false);
        }
        return null;
    }

    private static long[] concat(long[] a, long[] b, boolean reverseB) {
        long[] result = new long[a.length + b.length - 1];
        System.arraycopy(a, 0, result, 0, a.length);
        for (int i = 1; i < b.length; i++) {
            result[a.length - 1 + i] = reverseB ? b[b.length - 1 - i] : b[i];
        }
        return result;
    }

    private static long[] reversed(long[] values) {
        long[] result = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[values.length - 1 - i];
        }
        return result;
    }
}
