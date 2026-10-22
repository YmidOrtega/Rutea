package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import me.ymid.rutea.geo.OsmBoundaries;
import me.ymid.rutea.geo.geometry.Simplify;

/**
 * Extrae de OSM las UPZ de la localidad, los sectores en que se publican los horarios de paso (R04), y las guarda
 * en GeoJSON con los bordes simplificados.
 *
 * <p>Uso: {@code ExtractSectors <extracto.osm.pbf> <relación de las UPZ> <salida.geojson>}
 */
public final class ExtractSectors {

    /** Un contenedor junto a un borde puede caer del otro lado por esto; nunca más lejos. */
    static final double SIMPLIFY_METERS = 5;

    private ExtractSectors() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("uso: ExtractSectors <extracto.osm.pbf> <relación de las UPZ> <salida.geojson>");
            System.exit(2);
        }
        long start = System.nanoTime();
        long relation = Long.parseLong(args[1]);
        List<OsmBoundaries.Boundary> upz = OsmBoundaries.subareas(Path.of(args[0]), relation);
        Path out = Path.of(args[2]);
        Files.writeString(out, geoJson(upz, relation, Path.of(args[0]).getFileName().toString()));
        System.out.printf("%d UPZ en %.1f s → %s (%d KB)%n", upz.size(), (System.nanoTime() - start) / 1e9, out,
                Files.size(out) / 1024);
    }

    /** Una UPZ por línea, para que un cambio del extracto se lea en el diff. Coordenadas a 5 decimales (~1 m). */
    static String geoJson(List<OsmBoundaries.Boundary> upz, long relation, String extract) {
        StringBuilder json = new StringBuilder()
                .append("{\"type\":\"FeatureCollection\",\"source\":\"OpenStreetMap, subáreas de la relación ")
                .append(relation).append(", ").append(extract).append("; bordes simplificados a ")
                .append((int) SIMPLIFY_METERS).append(" m\",\"license\":\"ODbL 1.0, © colaboradores de OpenStreetMap\",")
                .append("\"features\":[\n");
        for (int u = 0; u < upz.size(); u++) {
            OsmBoundaries.Boundary boundary = upz.get(u);
            if (boundary.ringsLatE7().size() != 1) {
                // Un segundo anillo en un Polygon de GeoJSON es un hueco; si una UPZ trae dos partes, hay que escribirla distinto.
                throw new IllegalStateException(boundary.name() + " tiene " + boundary.ringsLatE7().size()
                        + " anillos; se esperaba uno");
            }
            json.append("{\"type\":\"Feature\",\"properties\":{\"ref\":\"").append(boundary.ref())
                    .append("\",\"name\":\"").append(boundary.name().replaceFirst("^UPZ ", ""))
                    .append("\",\"osmRelation\":").append(boundary.osmId())
                    .append("},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[");
            int[] lat = boundary.ringsLatE7().getFirst();
            int[] lon = boundary.ringsLonE7().getFirst();
            boolean[] keep = Simplify.ring(lat, lon, SIMPLIFY_METERS);
            json.append('[');
            for (int i = 0; i < lat.length; i++) {
                if (keep[i]) {
                    json.append(i == 0 ? "" : ",").append(point(lat[i], lon[i]));
                }
            }
            json.append(',').append(point(lat[0], lon[0])).append(']');
            json.append("]}}").append(u + 1 < upz.size() ? ",\n" : "\n");
        }
        return json.append("]}\n").toString();
    }

    private static String point(int latE7, int lonE7) {
        return String.format(Locale.ROOT, "[%.5f,%.5f]", lonE7 / 1e7, latE7 / 1e7);
    }
}
