package me.ymid.rutea.tools.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.geo.OsmBoundaries;
import me.ymid.rutea.geo.geometry.Polygon;
import me.ymid.rutea.geo.geometry.Simplify;

public final class ExtractSectors {

    /** Un contenedor junto a un borde puede caer del otro lado por esto; nunca más lejos. */
    static final double SIMPLIFY_METERS = 5;

    /**
     * Por número de localidad, la relación "UPZs Localidad …" de OSM, que lista sus UPZ como subáreas. Sumapaz es rural
     * y no se divide en UPZ.
     */
    static final Map<Integer, Long> UPZ_GROUPS = Map.ofEntries(
            Map.entry(1, 11249973L), Map.entry(2, 11249984L), Map.entry(3, 11251223L), Map.entry(4, 11257168L),
            Map.entry(5, 11257374L), Map.entry(6, 11509186L), Map.entry(7, 11495052L), Map.entry(8, 11348624L),
            Map.entry(9, 11364539L), Map.entry(10, 11375621L), Map.entry(11, 11486101L), Map.entry(12, 11495067L),
            Map.entry(13, 11502495L), Map.entry(14, 11502562L), Map.entry(15, 11509169L), Map.entry(16, 11502544L),
            Map.entry(17, 11251324L), Map.entry(18, 11512056L), Map.entry(19, 11296847L));

    /**
     * Códigos que OSM tiene mal en el extracto de 2026-10-19, por relación: La Candelaria lleva el 17 (el de San José
     * de Bavaria, en Suba) y Muzú el 42 (el de Venecia, en Tunjuelito). Los oficiales, 94 y 41, son los de las fichas
     * locales de la Secretaría General de 2021.
     */
    static final Map<Long, String> REF_FIXES = Map.of(11251322L, "94", 11502551L, "41");

    private ExtractSectors() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("uso: ExtractSectors <extracto.osm.pbf> <dir-salida>");
            System.exit(2);
        }
        long start = System.nanoTime();
        Path pbf = Path.of(args[0]);
        List<Locality> localities = new ArrayList<>();
        ServiceAreas.BOGOTA.forEach(area -> area.localities().stream().filter(l -> !l.rural()).forEach(localities::add));
        localities.sort(Comparator.comparingInt(Locality::number));
        Map<Long, List<OsmBoundaries.Boundary>> byGroup = OsmBoundaries.subareas(pbf,
                localities.stream().map(locality -> UPZ_GROUPS.get(locality.number())).toList());
        // Sectors ubica cada contenedor por el código de la UPZ entre las de su ASE: ahí tiene que ser único. Una UPZ
        // partida entre dos localidades de ASE distintas, como Parque Entrenubes, sale en las dos.
        for (ServiceAreas.ServiceArea area : ServiceAreas.BOGOTA) {
            Map<String, String> seen = new HashMap<>();
            for (Locality locality : area.localities()) {
                if (locality.rural()) {
                    continue;
                }
                for (OsmBoundaries.Boundary boundary : byGroup.get(UPZ_GROUPS.get(locality.number()))) {
                    String other = seen.putIfAbsent(ref(boundary), boundary.name() + " (" + locality.name() + ")");
                    if (ref(boundary).isBlank() || other != null) {
                        throw new IllegalStateException(area.name() + ": " + boundary.name() + " (" + locality.name()
                                + ") con código \"" + ref(boundary) + "\" vacío o repetido"
                                + (other != null ? ", igual a " + other : ""));
                    }
                }
            }
        }
        Path dir = Path.of(args[1]);
        Files.createDirectories(dir);
        int total = 0;
        for (Locality locality : localities) {
            long relation = UPZ_GROUPS.get(locality.number());
            List<OsmBoundaries.Boundary> upz = byGroup.get(relation);
            Path out = dir.resolve(locality.slug() + "-upz.geojson");
            Files.writeString(out, geoJson(upz, relation, pbf.getFileName().toString()));
            System.out.printf("%-20s %2d UPZ → %s (%d KB)%n", locality.name(), upz.size(), out.getFileName(),
                    Files.size(out) / 1024);
            total += upz.size();
        }
        System.out.printf("%d UPZ de %d localidades en %.1f s%n", total, localities.size(),
                (System.nanoTime() - start) / 1e9);
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
            int rings = boundary.ringsLatE7().size();
            for (int r = 0; r < rings; r++) {
                for (int other = 0; other < rings; other++) {
                    // Cada anillo se escribe como una parte; un hueco habría que escribirlo dentro de su parte.
                    if (other != r && ring(boundary, other).contains(boundary.ringsLatE7().get(r)[0],
                            boundary.ringsLonE7().get(r)[0])) {
                        throw new IllegalStateException(boundary.name() + " tiene un hueco; se esperaban solo partes");
                    }
                }
            }
            json.append("{\"type\":\"Feature\",\"properties\":{\"ref\":\"").append(ref(boundary))
                    .append("\",\"name\":\"").append(boundary.name().replaceFirst("^UPZ ", ""))
                    .append("\",\"osmRelation\":").append(boundary.osmId())
                    .append("},\"geometry\":{\"type\":\"").append(rings == 1 ? "Polygon" : "MultiPolygon")
                    .append("\",\"coordinates\":[");
            for (int r = 0; r < rings; r++) {
                json.append(rings == 1 ? "" : r == 0 ? "[" : ",[");
                int[] lat = boundary.ringsLatE7().get(r);
                int[] lon = boundary.ringsLonE7().get(r);
                boolean[] keep = Simplify.ring(lat, lon, SIMPLIFY_METERS);
                json.append('[');
                for (int i = 0; i < lat.length; i++) {
                    if (keep[i]) {
                        json.append(i == 0 ? "" : ",").append(point(lat[i], lon[i]));
                    }
                }
                json.append(',').append(point(lat[0], lon[0])).append(']');
                json.append(rings == 1 ? "" : "]");
            }
            json.append("]}}").append(u + 1 < upz.size() ? ",\n" : "\n");
        }
        return json.append("]}\n").toString();
    }

    static String ref(OsmBoundaries.Boundary boundary) {
        return REF_FIXES.getOrDefault(boundary.osmId(), boundary.ref());
    }

    private static Polygon ring(OsmBoundaries.Boundary boundary, int r) {
        return new Polygon(List.of(boundary.ringsLatE7().get(r)), List.of(boundary.ringsLonE7().get(r)));
    }

    static String point(int latE7, int lonE7) {
        return String.format(Locale.ROOT, "[%.5f,%.5f]", lonE7 / 1e7, latE7 / 1e7);
    }
}
