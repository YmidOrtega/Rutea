package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import me.ymid.rutea.app.scenario.OperatorContainers.Container;
import me.ymid.rutea.app.scenario.OperatorContainers.Layer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Descarga de las capas públicas de ArcGIS de los operadores los contenedores negros "en campo" (ADR 0013) y los
 * guarda en {@code data/ase/}, con un manifiesto de dónde y cuándo salió cada archivo. Las capas cambian cada mes:
 * Rutea planifica sobre el extracto, no sobre la capa viva.
 *
 * <p>Uso: {@code ExtractOperatorContainers <dir-salida>}
 */
public final class ExtractOperatorContainers {

    static final String MANIFEST = "fuentes.csv";

    private static final int PAGE = 1_000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ExtractOperatorContainers() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 1) {
            System.err.println("uso: ExtractOperatorContainers <dir-salida>");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        StringBuilder manifest = new StringBuilder("ase,file,containers,layer_edited,downloaded,sha256,url,where\n");
        for (Layer layer : OperatorContainers.LAYERS) {
            long start = System.nanoTime();
            JsonNode info = get(http, layer.url() + "?f=json");
            JsonNode edited = info.path("editingInfo").path("lastEditDate");
            String layerEdited = edited.isMissingNode() || edited.isNull() ? ""
                    : Instant.ofEpochMilli(edited.asLong()).toString();
            List<Container> containers = download(http, layer);
            Path csv = out.resolve(layer.file());
            OperatorContainers.write(csv, containers);
            String sha = sha256(csv);
            manifest.append(layer.area()).append(',').append(layer.file()).append(',').append(containers.size())
                    .append(',').append(layerEdited).append(',').append(Instant.now().toString(), 0, 10).append(',')
                    .append(sha).append(',').append(layer.url()).append(",\"").append(layer.where()).append("\"\n");
            Map<String, Integer> byLocality = new TreeMap<>();
            Map<Integer, Integer> byLiters = new TreeMap<>();
            Map<String, Integer> byShift = new TreeMap<>();
            containers.forEach(c -> {
                byLocality.merge(c.locality(), 1, Integer::sum);
                byLiters.merge(c.liters(), 1, Integer::sum);
                byShift.merge(c.shift(), 1, Integer::sum);
            });
            long distinct = containers.stream().map(Container::code).distinct().count();
            System.out.printf("ASE %d: %d contenedores (%d códigos distintos, %d fuera de la ASE) en %.1f s; litros %s;"
                    + " turnos %s; %s; capa editada %s%n  → %s%n", layer.area(), containers.size(), distinct,
                    outside(layer.area(), containers), (System.nanoTime() - start) / 1e9, byLiters, byShift,
                    byLocality, layerEdited, csv);
        }
        Path manifestFile = out.resolve(MANIFEST);
        Files.writeString(manifestFile, manifest);
        System.out.println("Manifiesto: " + manifestFile);
    }

    /** Un contenedor que cae en otra ASE delata un error de la capa o del extracto, como latitud y longitud cruzadas. */
    static long outside(int area, List<Container> containers) {
        ServiceAreas.ServiceArea serviceArea = ServiceAreas.BOGOTA.get(area - 1);
        Localities localities = Localities.bogota();
        return containers.stream()
                .filter(c -> !serviceArea.localities().contains(localities.locate(c.latE7(), c.lonE7()))).count();
    }

    static List<Container> download(HttpClient http, Layer layer) throws IOException, InterruptedException {
        List<Container> containers = new ArrayList<>();
        for (int offset = 0;; offset += PAGE) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("where", layer.where());
            query.put("outFields", String.join(",", layer.fields()));
            query.put("returnGeometry", "true");
            query.put("outSR", "4326");
            query.put("orderByFields", "OBJECTID");
            query.put("resultOffset", Integer.toString(offset));
            query.put("resultRecordCount", Integer.toString(PAGE));
            query.put("f", "json");
            JsonNode page = get(http, layer.url() + "/query?" + encode(query));
            List<Container> parsed = OperatorContainers.parse(layer, page);
            containers.addAll(parsed);
            if (!page.path("exceededTransferLimit").asBoolean(false) || parsed.isEmpty()) {
                return containers;
            }
        }
    }

    private static JsonNode get(HttpClient http, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Rutea (https://github.com/YmidOrtega/Rutea)").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(url + ": HTTP " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    private static String encode(Map<String, String> query) {
        List<String> pairs = new ArrayList<>();
        query.forEach((k, v) -> pairs.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return String.join("&", pairs);
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
