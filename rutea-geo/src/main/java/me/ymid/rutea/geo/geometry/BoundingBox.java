package me.ymid.rutea.geo.geometry;

/** Rectángulo en coordenadas de 1e-7 grados, con los bordes incluidos. */
public record BoundingBox(int minLatE7, int minLonE7, int maxLatE7, int maxLonE7) {

    public BoundingBox {
        if (minLatE7 > maxLatE7 || minLonE7 > maxLonE7) {
            throw new IllegalArgumentException("rectángulo vacío: " + minLatE7 + "," + minLonE7 + " a "
                    + maxLatE7 + "," + maxLonE7);
        }
    }

    public static BoundingBox of(int[] latE7, int[] lonE7) {
        if (latE7.length == 0) {
            throw new IllegalArgumentException("sin puntos");
        }
        int minLat = Integer.MAX_VALUE;
        int minLon = Integer.MAX_VALUE;
        int maxLat = Integer.MIN_VALUE;
        int maxLon = Integer.MIN_VALUE;
        for (int i = 0; i < latE7.length; i++) {
            minLat = Math.min(minLat, latE7[i]);
            maxLat = Math.max(maxLat, latE7[i]);
            minLon = Math.min(minLon, lonE7[i]);
            maxLon = Math.max(maxLon, lonE7[i]);
        }
        return new BoundingBox(minLat, minLon, maxLat, maxLon);
    }

    public boolean contains(int latE7, int lonE7) {
        return latE7 >= minLatE7 && latE7 <= maxLatE7 && lonE7 >= minLonE7 && lonE7 <= maxLonE7;
    }

    public BoundingBox expand(double meters) {
        int dLat = Haversine.latitudeE7(meters);
        // La longitud se estira más lejos del ecuador: se usa la latitud con el mayor valor absoluto.
        int farthest = Math.max(Math.abs(minLatE7), Math.abs(maxLatE7));
        int dLon = Haversine.longitudeE7(meters, farthest);
        return new BoundingBox(minLatE7 - dLat, minLonE7 - dLon, maxLatE7 + dLat, maxLonE7 + dLon);
    }

    public BoundingBox union(BoundingBox other) {
        return new BoundingBox(Math.min(minLatE7, other.minLatE7), Math.min(minLonE7, other.minLonE7),
                Math.max(maxLatE7, other.maxLatE7), Math.max(maxLonE7, other.maxLonE7));
    }
}
