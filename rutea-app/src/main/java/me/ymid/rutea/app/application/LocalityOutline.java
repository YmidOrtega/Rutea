package me.ymid.rutea.app.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import me.ymid.rutea.app.scenario.ServiceAreas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Una localidad como la dibuja el selector del mapa: su borde y la ASE que la atiende.
 *
 * @param outline el anillo exterior de cada parte, simplificado a 5 m y cerrado, como {@code [lon, lat]}
 */
public record LocalityOutline(int number, String name, int area, String operator, boolean rural,
        List<List<double[]>> outline) {

    public LocalityOutline {
        outline = List.copyOf(outline);
    }

    private static final String RESOURCE = "/localities/bogota-localidades.geojson";

    private static List<LocalityOutline> bogota;

    /** Las 20, por número, leídas una vez del mismo archivo que ubica cada contenedor en su localidad. */
    public static synchronized List<LocalityOutline> bogota() {
        if (bogota != null) {
            return bogota;
        }
        try (InputStream in = LocalityOutline.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("falta " + RESOURCE + "; se genera con mvn -pl rutea-tools -am compile exec:java@localities");
            }
            List<LocalityOutline> localities = new ArrayList<>();
            for (JsonNode feature : JsonMapper.builder().build().readTree(in).get("features")) {
                int number = Integer.parseInt(feature.get("properties").get("ref").asString());
                ServiceAreas.Locality locality = ServiceAreas.locality(number).orElseThrow();
                ServiceAreas.ServiceArea area = ServiceAreas.BOGOTA.stream()
                        .filter(candidate -> candidate.localities().contains(locality)).findFirst().orElseThrow();
                List<List<double[]>> outline = new ArrayList<>();
                for (JsonNode polygon : feature.get("geometry").get("coordinates")) {
                    List<double[]> ring = new ArrayList<>();
                    polygon.get(0).forEach(point -> ring.add(new double[] {point.get(0).asDouble(), point.get(1).asDouble()}));
                    outline.add(ring);
                }
                localities.add(new LocalityOutline(number, locality.name(), area.number(), area.operator(),
                        locality.rural(), outline));
            }
            localities.sort(Comparator.comparingInt(LocalityOutline::number));
            bogota = List.copyOf(localities);
            return bogota;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
