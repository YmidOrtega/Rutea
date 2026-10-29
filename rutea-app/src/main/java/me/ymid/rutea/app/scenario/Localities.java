package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.geometry.Polygon;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * De qué localidad es cada calle del grafo (ADR 0012). El grafo de Bogotá solo sabe si una calle está en el Distrito;
 * la localidad sale de los límites de OSM en {@code localities/bogota-localidades.geojson}, que se generan con
 * {@code mvn -pl rutea-app exec:java@localities}.
 */
public final class Localities {

    private static final String RESOURCE = "/localities/bogota-localidades.geojson";

    private static Localities bogota;

    private record Area(Locality locality, Polygon polygon) {
    }

    private final List<Area> areas;

    public Localities(Map<Locality, Polygon> polygons) {
        if (polygons.isEmpty()) {
            throw new IllegalArgumentException("no hay localidades");
        }
        List<Area> sorted = new ArrayList<>();
        polygons.forEach((locality, polygon) -> sorted.add(new Area(locality, polygon)));
        sorted.sort(Comparator.comparingInt(area -> area.locality().number()));
        areas = List.copyOf(sorted);
    }

    public static synchronized Localities bogota() {
        if (bogota == null) {
            try (InputStream in = Localities.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "falta " + RESOURCE + "; se genera con mvn -pl rutea-app exec:java@localities");
                }
                bogota = read(JsonMapper.builder().build().readTree(in));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return bogota;
    }

    static Localities read(JsonNode geoJson) {
        Map<Locality, Polygon> polygons = new HashMap<>();
        for (JsonNode feature : geoJson.get("features")) {
            int number = Integer.parseInt(feature.get("properties").get("ref").asString());
            Locality locality = ServiceAreas.locality(number).orElseThrow(
                    () -> new IllegalStateException(RESOURCE + " trae la localidad " + number + ", que no está en el catálogo"));
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
            polygons.put(locality, new Polygon(lat, lon));
        }
        return new Localities(polygons);
    }

    /**
     * La localidad que contiene el punto o, si ninguna, la de borde más cercano: los bordes van simplificados y muchos
     * siguen el eje de una avenida, así que una calle del Distrito puede quedar unos metros afuera de todas.
     */
    public Locality locate(int latE7, int lonE7) {
        for (Area area : areas) {
            if (area.polygon().contains(latE7, lonE7)) {
                return area.locality();
            }
        }
        Area nearest = areas.getFirst();
        double best = Double.POSITIVE_INFINITY;
        for (Area area : areas) {
            double meters = area.polygon().borderMeters(latE7, lonE7);
            if (meters < best) {
                best = meters;
                nearest = area;
            }
        }
        return nearest.locality();
    }

    /** La del punto medio del trazo, el mismo con el que el constructor del grafo decide si la calle está en el área. */
    public Locality ofShape(RoadGraph graph, int shape) {
        int first = graph.firstShapePoint(shape);
        int middle = first + (graph.endShapePoint(shape) - first) / 2;
        return locate(graph.shapeLatE7(middle), graph.shapeLonE7(middle));
    }
}
