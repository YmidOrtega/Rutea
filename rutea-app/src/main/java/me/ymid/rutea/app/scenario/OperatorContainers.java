package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;

/**
 * Los contenedores negros que cada operador publica "en campo" en su capa de ArcGIS (ADR 0013). Solo las ASE 1, 2 y
 * 4 publican la ubicación; las fuentes y su fecha de corte están en {@code docs/datos.md} y {@code data/ase/README.md}.
 */
public final class OperatorContainers {

    /**
     * Una capa de ArcGIS y qué campo trae cada dato. {@code where} deja solo los no aprovechables que están en la
     * calle: cada operador lo marca a su manera.
     */
    public record Layer(int area, String url, String where, String codeField, String litersField,
            String localityField, String routeField) {

        public String file() {
            return "ase" + area + "-contenedores-negros.csv";
        }

        List<String> fields() {
            return List.of(codeField, litersField, localityField, routeField);
        }
    }

    /** La localidad y la microrruta van como las escribe el operador; la localidad del catálogo sale por polígono. */
    public record Container(String code, int latE7, int lonE7, int liters, String locality, String route) {
    }

    public static final List<Layer> LAYERS = List.of(
            new Layer(1, "https://services6.arcgis.com/hLZWCvqGnXcO7AAf/arcgis/rest/services/C0nt3n3d0r3s/FeatureServer/0",
                    "NOVUBICACI='UC' AND APROVECHAB='N'", "CODINTERNO", "CAPACIDAD", "LOCALIDAD", "Micro"),
            // El servicio lleva la fecha de corte en el nombre: cada mes LIME publica uno nuevo.
            new Layer(2, "https://services8.arcgis.com/OJRjFyzrZGsrmOrJ/arcgis/rest/services/CONTENEDORES_OCTUBRE_2026/FeatureServer/0",
                    "NOVUBICACI='UC' AND APROVECHAB='N'", "CODIGO", "CAPACIDAD", "LOCALIDAD", "MICRORRUTA"),
            new Layer(4, "https://services1.arcgis.com/ENZ399MMKDHf2s6t/arcgis/rest/services/Contenerizacion_ASE4_2400_Actualizacion/FeatureServer/0",
                    "Definitivo='Ruta' AND APROVECHAB='N'", "CODINTERNO", "CAPACIDAD", "IDLOCALID", "MICRO"));

    private static final String HEADER = "code,lat_e7,lon_e7,liters,locality,route";

    private OperatorContainers() {
    }

    /** Los contenedores de una página de {@code /query?f=json} con {@code outSR=4326}. */
    static List<Container> parse(Layer layer, JsonNode page) {
        if (page.has("error")) {
            throw new IllegalStateException("ASE " + layer.area() + ": ArcGIS respondió " + page.get("error"));
        }
        List<Container> containers = new ArrayList<>();
        for (JsonNode feature : page.get("features")) {
            JsonNode attributes = feature.get("attributes");
            String code = text(attributes, layer.codeField());
            JsonNode geometry = feature.get("geometry");
            if (geometry == null || geometry.isNull() || !geometry.has("x")) {
                throw new IllegalStateException("ASE " + layer.area() + ": el contenedor " + code + " no tiene ubicación");
            }
            containers.add(new Container(code, (int) Math.round(geometry.get("y").asDouble() * 1e7),
                    (int) Math.round(geometry.get("x").asDouble() * 1e7),
                    Integer.parseInt(text(attributes, layer.litersField())), text(attributes, layer.localityField()),
                    text(attributes, layer.routeField())));
        }
        return containers;
    }

    /** Ordenados por código y ubicación, para que el mismo extracto dé los mismos bytes. */
    public static void write(Path csv, List<Container> containers) throws IOException {
        List<Container> sorted = new ArrayList<>(containers);
        sorted.sort(Comparator.comparing(Container::code).thenComparingInt(Container::latE7)
                .thenComparingInt(Container::lonE7));
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Container c : sorted) {
            out.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%s,%s%n", csvField(c.code()), c.latE7(), c.lonE7(),
                    c.liters(), csvField(c.locality()), csvField(c.route())));
        }
        Files.writeString(csv, out);
    }

    public static List<Container> read(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) {
            throw new IOException(csv + ": se esperaba el encabezado " + HEADER);
        }
        List<Container> containers = new ArrayList<>(lines.size() - 1);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            if (f.length != 6) {
                throw new IOException(csv + ": línea con " + f.length + " campos: " + line);
            }
            containers.add(new Container(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                    f[4], f[5]));
        }
        return containers;
    }

    private static String text(JsonNode attributes, String field) {
        JsonNode value = attributes.get(field);
        if (value == null) {
            throw new IllegalStateException("la capa no trae el campo " + field);
        }
        return value.isNull() ? "" : value.asString().trim();
    }

    /** Los campos del operador no traen comas en lo que se ha visto; si aparece una, se reemplaza para no romper el CSV. */
    private static String csvField(String value) {
        return value.replace(',', ' ').replace('\n', ' ');
    }
}
