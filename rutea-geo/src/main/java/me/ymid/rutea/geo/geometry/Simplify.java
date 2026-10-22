package me.ymid.rutea.geo.geometry;

import java.util.Arrays;

/** Douglas-Peucker: quita los vértices que se apartan menos de una tolerancia, en metros, de la línea que queda. */
public final class Simplify {

    private Simplify() {
    }

    /**
     * Qué vértices de un anillo cerrado se quedan. Se parte en el vértice 0 y el más lejano a él, que siempre se
     * quedan, para que el anillo nunca colapse a una línea.
     */
    public static boolean[] ring(int[] latE7, int[] lonE7, double meters) {
        int n = latE7.length;
        if (n != lonE7.length) {
            throw new IllegalArgumentException("latitudes y longitudes de distinto largo");
        }
        boolean[] keep = new boolean[n];
        if (n <= 3) {
            Arrays.fill(keep, true);
            return keep;
        }
        double cosLat = Math.cos(Math.toRadians(latE7[0] / 1e7));
        double tolerance = meters / Haversine.EARTH_RADIUS_METERS * 180 / Math.PI * 1e7;
        int far = 0;
        double farthest = -1;
        for (int i = 1; i < n; i++) {
            double d = Math.hypot((lonE7[i] - lonE7[0]) * cosLat, latE7[i] - latE7[0]);
            if (d > farthest) {
                farthest = d;
                far = i;
            }
        }
        keep[0] = true;
        keep[far] = true;
        mark(latE7, lonE7, cosLat, tolerance, 0, far, keep);
        mark(latE7, lonE7, cosLat, tolerance, far, n, keep);
        return keep;
    }

    /** Entre {@code from} y {@code to} (el índice {@code n} vuelve al vértice 0). */
    private static void mark(int[] lat, int[] lon, double cosLat, double tolerance, int from, int to, boolean[] keep) {
        int n = lat.length;
        int[] stack = new int[2 * n + 2];
        int top = 0;
        stack[top++] = from;
        stack[top++] = to;
        while (top > 0) {
            int b = stack[--top];
            int a = stack[--top];
            double ax = lon[a] * cosLat;
            double ay = lat[a];
            double bx = lon[b % n] * cosLat;
            double by = lat[b % n];
            double dx = bx - ax;
            double dy = by - ay;
            double length = Math.hypot(dx, dy);
            int worst = -1;
            double worstDistance = tolerance;
            for (int i = a + 1; i < b; i++) {
                double px = lon[i] * cosLat - ax;
                double py = lat[i] - ay;
                double d = length == 0 ? Math.hypot(px, py) : Math.abs(px * dy - py * dx) / length;
                if (d > worstDistance) {
                    worstDistance = d;
                    worst = i;
                }
            }
            if (worst >= 0) {
                keep[worst] = true;
                stack[top++] = a;
                stack[top++] = worst;
                stack[top++] = worst;
                stack[top++] = b;
            }
        }
    }
}
