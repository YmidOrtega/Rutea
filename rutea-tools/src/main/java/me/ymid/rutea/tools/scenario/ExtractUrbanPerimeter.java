package me.ymid.rutea.tools.scenario;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import me.ymid.rutea.geo.geometry.Simplify;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ExtractUrbanPerimeter {

    static final String LAYER =
            "https://sinu.sdp.gov.co/serverp/rest/services/POT555/NORMA_URBAN%C3%8DSTICA_Y_OT/MapServer/28";

    static final int URBAN = 1;

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private ExtractUrbanPerimeter() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 1) {
            System.err.println("uso: ExtractUrbanPerimeter <salida.geojson>");
            System.exit(2);
        }
        long start = System.nanoTime();
        String query = LAYER + "/query?where=" + URLEncoder.encode("CLASE_SUELO=" + URBAN, StandardCharsets.UTF_8)
                + "&outFields=OBJECTID,ACTO_ADMINISTRATIVO,NUMERO_ACTO_ADMINISTRATIVO,FECHA_ACTO_ADMINISTRATIVO,"
                + "OBSERVACION&orderByFields=OBJECTID&outSR=4326&geometryPrecision=7&f=geojson";
        HttpRequest request = HttpRequest.newBuilder(URI.create(query)).timeout(Duration.ofSeconds(120))
                .header("User-Agent", "Rutea (https://github.com/YmidOrtega/Rutea)").GET().build();
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(query + ": HTTP " + response.statusCode());
        }
        JsonNode features = JsonMapper.builder().build().readTree(response.body()).path("features");
        if (features.isEmpty()) {
            throw new IOException("la capa no devolvió suelo urbano: " + response.body());
        }
        Path out = Path.of(args[0]);
        Files.writeString(out, geoJson(features, LocalDate.now(BOGOTA)));
        System.out.printf("%d polígonos de suelo urbano en %.1f s → %s (%d KB)%n", features.size(),
                (System.nanoTime() - start) / 1e9, out, Files.size(out) / 1024);
    }

    static String geoJson(JsonNode features, LocalDate downloaded) {
        StringBuilder json = new StringBuilder()
                .append("{\"type\":\"FeatureCollection\",\"source\":\"Secretaría Distrital de Planeación, POT de")
                .append(" Bogotá (Decreto 555 de 2021), clasificación del suelo, clase urbana; ").append(LAYER)
                .append("; descargado el ").append(downloaded).append("; bordes simplificados a ")
                .append((int) ExtractSectors.SIMPLIFY_METERS)
                .append(" m\",\"license\":\"CC BY 4.0, Secretaría Distrital de Planeación\",\"features\":[\n");
        for (int f = 0; f < features.size(); f++) {
            JsonNode feature = features.get(f);
            JsonNode properties = feature.get("properties");
            json.append("{\"type\":\"Feature\",\"properties\":{\"objectId\":").append(properties.get("OBJECTID").asInt())
                    .append(",\"act\":\"").append(properties.get("ACTO_ADMINISTRATIVO").asString()).append(' ')
                    .append(properties.get("NUMERO_ACTO_ADMINISTRATIVO").asString()).append("\",\"actDate\":\"")
                    .append(Instant.ofEpochMilli(properties.get("FECHA_ACTO_ADMINISTRATIVO").asLong())
                            .atZone(BOGOTA).toLocalDate())
                    .append("\"},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[");
            boolean firstRing = true;
            for (JsonNode ring : rings(feature.get("geometry"))) {
                int n = ring.size() - 1;
                int[] lat = new int[n];
                int[] lon = new int[n];
                for (int i = 0; i < n; i++) {
                    lon[i] = (int) Math.round(ring.get(i).get(0).asDouble() * 1e7);
                    lat[i] = (int) Math.round(ring.get(i).get(1).asDouble() * 1e7);
                }
                boolean[] keep = Simplify.ring(lat, lon, ExtractSectors.SIMPLIFY_METERS);
                int kept = 0;
                for (boolean k : keep) {
                    kept += k ? 1 : 0;
                }
                if (kept < 3) {
                    continue;
                }
                json.append(firstRing ? "" : ",").append("[[");
                firstRing = false;
                for (int i = 0; i < n; i++) {
                    if (keep[i]) {
                        json.append(i == 0 ? "" : ",").append(ExtractSectors.point(lat[i], lon[i]));
                    }
                }
                json.append(',').append(ExtractSectors.point(lat[0], lon[0])).append("]]");
            }
            json.append("]}}").append(f + 1 < features.size() ? ",\n" : "\n");
        }
        return json.append("]}\n").toString();
    }

    private static List<JsonNode> rings(JsonNode geometry) {
        List<JsonNode> rings = new ArrayList<>();
        switch (geometry.get("type").asString()) {
            case "Polygon" -> geometry.get("coordinates").forEach(rings::add);
            case "MultiPolygon" -> geometry.get("coordinates").forEach(polygon -> polygon.forEach(rings::add));
            default -> throw new IllegalArgumentException("geometría " + geometry.get("type") + " no es un polígono");
        }
        return rings;
    }
}
