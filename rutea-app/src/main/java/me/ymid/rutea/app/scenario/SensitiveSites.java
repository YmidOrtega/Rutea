package me.ymid.rutea.app.scenario;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import me.ymid.rutea.geo.OsmFeatures;
import me.ymid.rutea.geo.geometry.Haversine;

/**
 * Sitios donde la norma cambia la operación: frente a ellos no se compacta (R07) o, de noche, se mitiga el ruido
 * (R02). Salen de las etiquetas de OSM; el mapeo de etiquetas a tipo de sitio es una interpretación documentada en
 * {@code docs/normativa.md}.
 */
public final class SensitiveSites {

    /**
     * "Frente a" no tiene distancia en la norma: se toma 50 m entre el contenedor y el sitio (su nodo, o el vértice
     * más cercano de su predio). {@code PENDIENTE_VERIFICAR} con el operador o la autoridad.
     */
    public static final int FRONT_METERS = 50;

    public enum Kind {
        EDUCATION("centro educativo", true, false),
        HEALTH("hospital o clínica", true, true),
        CARE("centro asistencial", true, true),
        HOTEL("hotel", false, true);

        private final String label;
        private final boolean noCompaction;
        private final boolean nightNoise;

        Kind(String label, boolean noCompaction, boolean nightNoise) {
            this.label = label;
            this.noCompaction = noCompaction;
            this.nightNoise = nightNoise;
        }

        public String label() {
            return label;
        }

        /** R07: nunca se compacta frente a centros educativos, hospitales, clínicas o centros asistenciales. */
        public boolean noCompaction() {
            return noCompaction;
        }

        /** R02: de 21:00 a 06:00, en hoteles, hospitales, clínicas y centros asistenciales se mitiga el ruido. */
        public boolean nightNoise() {
            return nightNoise;
        }
    }

    public record Site(Kind kind, String osmRef, String name, int[] latE7, int[] lonE7) {

        /** Metros del punto al sitio: a su nodo, o al vértice más cercano de su predio. */
        public double metersTo(int lat, int lon) {
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < latE7.length; i++) {
                best = Math.min(best, Haversine.meters(lat, lon, latE7[i], lonE7[i]));
            }
            return best;
        }

        public String label() {
            return name.isBlank() ? kind.label() : name;
        }
    }

    private static final Set<String> EDUCATION = Set.of("school", "kindergarten", "college", "university");
    private static final Set<String> HEALTH = Set.of("hospital", "clinic");
    private static final Set<String> CARE = Set.of("nursing_home", "social_facility");

    private SensitiveSites() {
    }

    /** Los consultorios ({@code amenity=doctors}) no cuentan: no son claramente un centro asistencial. */
    public static Optional<Kind> kindOf(Map<String, String> tags) {
        String amenity = tags.getOrDefault("amenity", "");
        if (EDUCATION.contains(amenity)) {
            return Optional.of(Kind.EDUCATION);
        }
        if (HEALTH.contains(amenity) || HEALTH.contains(tags.getOrDefault("healthcare", ""))) {
            return Optional.of(Kind.HEALTH);
        }
        if (CARE.contains(amenity)) {
            return Optional.of(Kind.CARE);
        }
        if ("hotel".equals(tags.get("tourism"))) {
            return Optional.of(Kind.HOTEL);
        }
        return Optional.empty();
    }

    public static List<Site> fromOsm(List<OsmFeatures.Feature> features) {
        List<Site> sites = new ArrayList<>();
        for (OsmFeatures.Feature feature : features) {
            kindOf(feature.tags()).ifPresent(kind -> sites.add(new Site(kind, feature.osmType() + "/" + feature.osmId(),
                    feature.name(), feature.latE7(), feature.lonE7())));
        }
        return sites;
    }

    /** CSV: tipo, ref OSM, nombre (sin comas), puntos "lat:lon" separados por ";". */
    public static void write(Path file, List<Site> sites) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("kind,osm,name,points_e7\n");
            for (Site site : sites) {
                StringBuilder points = new StringBuilder();
                for (int i = 0; i < site.latE7().length; i++) {
                    points.append(i == 0 ? "" : ";").append(site.latE7()[i]).append(':').append(site.lonE7()[i]);
                }
                out.write(site.kind() + "," + site.osmRef() + "," + site.name().replace(',', ' ') + "," + points + "\n");
            }
        }
    }

    public static List<Site> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Site> sites = new ArrayList<>();
        for (int n = 1; n < lines.size(); n++) {
            String[] cells = lines.get(n).split(",", -1);
            if (cells.length != 4) {
                throw new IOException(file.getFileName() + ":" + (n + 1) + ": se esperaban 4 columnas");
            }
            String[] points = cells[3].split(";");
            int[] lat = new int[points.length];
            int[] lon = new int[points.length];
            for (int i = 0; i < points.length; i++) {
                String[] latLon = points[i].split(":");
                lat[i] = Integer.parseInt(latLon[0]);
                lon[i] = Integer.parseInt(latLon[1]);
            }
            sites.add(new Site(Kind.valueOf(cells[0]), cells[1], cells[2], lat, lon));
        }
        return sites;
    }
}
