package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.geometry.Polygon;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class UrbanPerimeter {

    private static final String RESOURCE = "/localities/bogota-perimetro-urbano.geojson";

    private static UrbanPerimeter bogota;

    private final List<Polygon> polygons;

    public UrbanPerimeter(List<Polygon> polygons) {
        if (polygons.isEmpty()) {
            throw new IllegalArgumentException("el perímetro urbano no tiene polígonos");
        }
        this.polygons = List.copyOf(polygons);
    }

    public static synchronized UrbanPerimeter bogota() {
        if (bogota == null) {
            try (InputStream in = UrbanPerimeter.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "falta " + RESOURCE + "; se genera con mvn -pl rutea-tools -am compile exec:java@urban-perimeter");
                }
                bogota = read(JsonMapper.builder().build().readTree(in));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return bogota;
    }

    static UrbanPerimeter read(JsonNode geoJson) {
        List<Polygon> polygons = new ArrayList<>();
        for (JsonNode feature : geoJson.get("features")) {
            List<int[]> lat = new ArrayList<>();
            List<int[]> lon = new ArrayList<>();
            for (JsonNode polygon : feature.get("geometry").get("coordinates")) {
                JsonNode ring = polygon.get(0);
                int n = ring.size() - 1;
                int[] ringLat = new int[n];
                int[] ringLon = new int[n];
                for (int i = 0; i < n; i++) {
                    ringLon[i] = (int) Math.round(ring.get(i).get(0).asDouble() * 1e7);
                    ringLat[i] = (int) Math.round(ring.get(i).get(1).asDouble() * 1e7);
                }
                lat.add(ringLat);
                lon.add(ringLon);
            }
            polygons.add(new Polygon(lat, lon));
        }
        return new UrbanPerimeter(polygons);
    }

    public boolean contains(int latE7, int lonE7) {
        for (Polygon polygon : polygons) {
            if (polygon.contains(latE7, lonE7)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsShape(RoadGraph graph, int shape) {
        int first = graph.firstShapePoint(shape);
        int middle = first + (graph.endShapePoint(shape) - first) / 2;
        return contains(graph.shapeLatE7(middle), graph.shapeLonE7(middle));
    }
}
