package me.ymid.rutea.geo.geometry;

/** Distancia sobre la esfera. A la escala de una ciudad, el error frente al elipsoide es menor al 0,5 %. */
public final class Haversine {

    /** Radio medio de la Tierra (IUGG), en metros. */
    public static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private static final double E7_TO_RADIANS = Math.PI / 180 / 1e7;

    private Haversine() {
    }

    public static double meters(int latE7a, int lonE7a, int latE7b, int lonE7b) {
        double lat1 = latE7a * E7_TO_RADIANS;
        double lat2 = latE7b * E7_TO_RADIANS;
        double sinDLat = Math.sin((lat2 - lat1) / 2);
        double sinDLon = Math.sin((lonE7b - lonE7a) * E7_TO_RADIANS / 2);
        double h = sinDLat * sinDLat + Math.cos(lat1) * Math.cos(lat2) * sinDLon * sinDLon;
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    public static int latitudeE7(double meters) {
        return (int) Math.ceil(meters / EARTH_RADIUS_METERS * 180 / Math.PI * 1e7);
    }

    public static int longitudeE7(double meters, int latE7) {
        return (int) Math.ceil(latitudeE7(meters) / Math.cos(latE7 * E7_TO_RADIANS));
    }
}
