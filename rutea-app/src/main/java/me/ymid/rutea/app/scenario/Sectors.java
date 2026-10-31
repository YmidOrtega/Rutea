package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.ServiceAreas.ServiceArea;
import me.ymid.rutea.geo.geometry.Polygon;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las UPZ de las localidades de una ASE, los sectores en que se publican los horarios de paso (R04). Cada localidad
 * urbana tiene las suyas en {@code sectors/<localidad>-upz.geojson}, que salen de OSM con
 * {@code mvn -pl rutea-tools -am compile exec:java@sectors}; el suelo rural es un solo sector, {@link #RURAL}.
 */
public final class Sectors {

    /**
     * Un contenedor más cerca que esto del borde de una UPZ es de ella: los bordes van simplificados y muchos siguen
     * el eje de una avenida. En Suba, los que quedan afuera están a 24 m o menos, o a 33 m o más.
     */
    static final double NEAR_BORDER_METERS = 30;

    /** El suelo rural de Bogotá no se divide en UPZ: lo que queda fuera de todas es un sector aparte, sin borde. */
    public static final String RURAL = "rural";

    public static final String RURAL_NAME = "Zona rural";

    public record Upz(String ref, String name, Polygon polygon, List<double[]> outline) {
    }

    private static final Map<Integer, Sectors> BY_AREA = new HashMap<>();

    private final List<Upz> upz;

    private Sectors(List<Upz> upz) {
        this.upz = List.copyOf(upz);
    }

    public List<Upz> upz() {
        return upz;
    }

    public static synchronized Sectors of(ServiceArea area) {
        Sectors sectors = BY_AREA.get(area.number());
        if (sectors == null) {
            List<Upz> upz = new ArrayList<>();
            for (Locality locality : area.localities()) {
                if (locality.rural()) {
                    continue;
                }
                String resource = "/sectors/" + locality.slug() + "-upz.geojson";
                try (InputStream in = Sectors.class.getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IllegalStateException(
                                "falta " + resource + "; se genera con mvn -pl rutea-tools -am compile exec:java@sectors");
                    }
                    upz.addAll(upz(JsonMapper.builder().build().readTree(in)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            sectors = new Sectors(upz);
            BY_AREA.put(area.number(), sectors);
        }
        return sectors;
    }

    public static Sectors read(JsonNode geoJson) {
        return new Sectors(upz(geoJson));
    }

    private static List<Upz> upz(JsonNode geoJson) {
        List<Upz> upz = new ArrayList<>();
        for (JsonNode feature : geoJson.get("features")) {
            JsonNode geometry = feature.get("geometry");
            List<JsonNode> rings = new ArrayList<>();
            if (geometry.get("type").asString().equals("MultiPolygon")) {
                geometry.get("coordinates").forEach(part -> rings.add(part.get(0)));
            } else {
                rings.add(geometry.get("coordinates").get(0));
            }
            List<int[]> lats = new ArrayList<>();
            List<int[]> lons = new ArrayList<>();
            List<double[]> outline = List.of();
            double largest = -1;
            for (JsonNode ring : rings) {
                int n = ring.size() - 1;
                int[] lat = new int[n];
                int[] lon = new int[n];
                List<double[]> points = new ArrayList<>(n + 1);
                double area = 0;
                for (int i = 0; i <= n; i++) {
                    double lonDegrees = ring.get(i).get(0).asDouble();
                    double latDegrees = ring.get(i).get(1).asDouble();
                    points.add(new double[] {lonDegrees, latDegrees});
                    if (i < n) {
                        lat[i] = (int) Math.round(latDegrees * 1e7);
                        lon[i] = (int) Math.round(lonDegrees * 1e7);
                    }
                    if (i > 0) {
                        double[] previous = points.get(i - 1);
                        area += previous[0] * latDegrees - lonDegrees * previous[1];
                    }
                }
                lats.add(lat);
                lons.add(lon);
                // El plan dibuja un solo borde por sector: el de la parte más grande. Ubicar usa todas.
                if (Math.abs(area) > largest) {
                    largest = Math.abs(area);
                    outline = points;
                }
            }
            JsonNode properties = feature.get("properties");
            upz.add(new Upz(properties.get("ref").asString(), properties.get("name").asString(),
                    new Polygon(lats, lons), outline));
        }
        return upz;
    }

    /** La UPZ que contiene el punto, la de borde más cercano a menos de {@link #NEAR_BORDER_METERS}, o {@link #RURAL}. */
    public String locate(int latE7, int lonE7) {
        for (Upz sector : upz) {
            if (sector.polygon().contains(latE7, lonE7)) {
                return sector.ref();
            }
        }
        Upz nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (Upz sector : upz) {
            double meters = sector.polygon().borderMeters(latE7, lonE7);
            if (meters < best) {
                best = meters;
                nearest = sector;
            }
        }
        return nearest != null && best <= NEAR_BORDER_METERS ? nearest.ref() : RURAL;
    }
}
