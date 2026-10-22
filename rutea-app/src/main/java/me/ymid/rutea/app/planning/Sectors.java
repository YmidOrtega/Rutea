package me.ymid.rutea.app.planning;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.geometry.Haversine;
import me.ymid.rutea.geo.geometry.Polygon;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las UPZ de la localidad, los sectores en que se publican los horarios de paso (R04). Salen de OSM con
 * {@code mvn -pl rutea-app exec:java@sectors}, que reescribe {@code sectors/suba-upz.geojson}.
 */
public final class Sectors {

    /** Un vehículo que vuelve al sector después de este tiempo hace otra pasada, casi siempre en otro viaje. */
    static final int PASS_GAP_SECONDS = 30 * 60;

    /** Las ventanas se publican en cuartos de hora, hacia afuera: nadie saca la bolsa tarde por un redondeo. */
    static final int ROUND_SECONDS = 15 * 60;

    /**
     * Un contenedor más cerca que esto del borde de una UPZ es de ella: los bordes van simplificados y muchos siguen
     * el eje de una avenida. En Suba, los que quedan afuera están a 24 m o menos, o a 33 m o más.
     */
    static final double NEAR_BORDER_METERS = 30;

    /** El suelo rural de Bogotá no se divide en UPZ: lo que queda fuera de todas es un sector aparte, sin borde. */
    public static final String RURAL = "rural";

    private static final String RURAL_NAME = "Zona rural";

    private static final String SUBA = "/sectors/suba-upz.geojson";

    private record Upz(String ref, String name, Polygon polygon, List<double[]> outline, int[] lat, int[] lon) {
    }

    private static Sectors suba;

    private final List<Upz> upz;

    private Sectors(List<Upz> upz) {
        this.upz = List.copyOf(upz);
    }

    public static synchronized Sectors suba() {
        if (suba == null) {
            try (InputStream in = Sectors.class.getResourceAsStream(SUBA)) {
                if (in == null) {
                    throw new IllegalStateException("falta " + SUBA + "; se genera con mvn -pl rutea-app exec:java@sectors");
                }
                suba = read(JsonMapper.builder().build().readTree(in));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return suba;
    }

    static Sectors read(JsonNode geoJson) {
        List<Upz> upz = new ArrayList<>();
        for (JsonNode feature : geoJson.get("features")) {
            JsonNode ring = feature.get("geometry").get("coordinates").get(0);
            int n = ring.size() - 1;
            int[] lat = new int[n];
            int[] lon = new int[n];
            List<double[]> outline = new ArrayList<>(n + 1);
            for (int i = 0; i <= n; i++) {
                double lonDegrees = ring.get(i).get(0).asDouble();
                double latDegrees = ring.get(i).get(1).asDouble();
                outline.add(new double[] {lonDegrees, latDegrees});
                if (i < n) {
                    lat[i] = (int) Math.round(latDegrees * 1e7);
                    lon[i] = (int) Math.round(lonDegrees * 1e7);
                }
            }
            JsonNode properties = feature.get("properties");
            upz.add(new Upz(properties.get("ref").asString(), properties.get("name").asString(),
                    new Polygon(List.of(lat), List.of(lon)), outline, lat, lon));
        }
        return new Sectors(upz);
    }

    /** La UPZ que contiene el punto, la de borde más cercano a menos de {@link #NEAR_BORDER_METERS}, o {@link #RURAL}. */
    public String locate(int latE7, int lonE7) {
        for (Upz sector : upz) {
            if (sector.polygon().contains(latE7, lonE7)) {
                return sector.ref();
            }
        }
        double cosLat = Math.cos(Math.toRadians(latE7 / 1e7));
        Upz nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (Upz sector : upz) {
            double d = squaredToRing(sector.lat(), sector.lon(), latE7, lonE7, cosLat);
            if (d < best) {
                best = d;
                nearest = sector;
            }
        }
        double meters = Math.sqrt(best) / 1e7 * Math.toRadians(1) * Haversine.EARTH_RADIUS_METERS;
        return nearest != null && meters <= NEAR_BORDER_METERS ? nearest.ref() : RURAL;
    }

    private static double squaredToRing(int[] lat, int[] lon, int latE7, int lonE7, double cosLat) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0, j = lat.length - 1; i < lat.length; j = i++) {
            double ax = (lon[j] - lonE7) * cosLat;
            double ay = lat[j] - latE7;
            double bx = (lon[i] - lonE7) * cosLat;
            double by = lat[i] - latE7;
            double dx = bx - ax;
            double dy = by - ay;
            double length = dx * dx + dy * dy;
            double t = length == 0 ? 0 : Math.clamp(-(ax * dx + ay * dy) / length, 0, 1);
            double x = ax + t * dx;
            double y = ay + t * dy;
            best = Math.min(best, x * x + y * y);
        }
        return best;
    }

    /**
     * Cuándo pasa cada vehículo por cada UPZ, según las llegadas planeadas: una pasada va de la primera a la última
     * llegada a sus contenedores del sector, redondeada hacia afuera al cuarto de hora.
     */
    public List<DemoPlan.Sector> schedule(List<DemoPlan.Container> containers) {
        Map<String, Map<String, List<DemoPlan.Container>>> bySector = new LinkedHashMap<>();
        upz.forEach(sector -> bySector.put(sector.ref(), new TreeMap<>()));
        bySector.put(RURAL, new TreeMap<>());
        for (DemoPlan.Container container : containers) {
            bySector.get(container.sector()).computeIfAbsent(container.route(), route -> new ArrayList<>())
                    .add(container);
        }
        List<Upz> all = new ArrayList<>(upz);
        all.add(new Upz(RURAL, RURAL_NAME, null, List.of(), new int[0], new int[0]));
        List<DemoPlan.Sector> sectors = new ArrayList<>();
        for (Upz sector : all) {
            if (bySector.get(sector.ref()).isEmpty()) {
                continue;
            }
            List<DemoPlan.Pass> passes = new ArrayList<>();
            bySector.get(sector.ref()).forEach((route, visited) -> {
                visited.sort(Comparator.comparingInt(DemoPlan.Container::visitSeconds));
                WasteStream stream = visited.getFirst().stream();
                int from = visited.getFirst().visitSeconds();
                int last = from;
                int count = 0;
                for (DemoPlan.Container container : visited) {
                    if (container.visitSeconds() - last > PASS_GAP_SECONDS) {
                        passes.add(pass(stream, route, from, last, count));
                        from = container.visitSeconds();
                        count = 0;
                    }
                    last = container.visitSeconds();
                    count++;
                }
                passes.add(pass(stream, route, from, last, count));
            });
            passes.sort(Comparator.comparingInt(DemoPlan.Pass::fromSeconds).thenComparing(DemoPlan.Pass::route));
            sectors.add(new DemoPlan.Sector(sector.ref(), sector.name(), sector.outline(), passes));
        }
        return sectors;
    }

    private static DemoPlan.Pass pass(WasteStream stream, String route, int first, int last, int containers) {
        int from = Math.floorDiv(first, ROUND_SECONDS) * ROUND_SECONDS;
        int to = Math.max(from + ROUND_SECONDS, -Math.floorDiv(-last, ROUND_SECONDS) * ROUND_SECONDS);
        return new DemoPlan.Pass(stream, route, from, to, containers);
    }
}
