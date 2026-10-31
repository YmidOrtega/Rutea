package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.geo.OsmBoundaries;
import me.ymid.rutea.geo.geometry.Simplify;

public final class ExtractLocalities {

    private ExtractLocalities() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("uso: ExtractLocalities <extracto.osm.pbf> <salida.geojson>");
            System.exit(2);
        }
        long start = System.nanoTime();
        List<Locality> localities = ServiceAreas.BOGOTA.stream().flatMap(area -> area.localities().stream())
                .sorted((a, b) -> Integer.compare(a.number(), b.number())).toList();
        List<OsmBoundaries.Boundary> boundaries = OsmBoundaries.of(Path.of(args[0]),
                localities.stream().map(Locality::osmRelation).toList());
        Path out = Path.of(args[1]);
        Files.writeString(out, geoJson(localities, boundaries, Path.of(args[0]).getFileName().toString()));
        System.out.printf("%d localidades en %.1f s → %s (%d KB)%n", boundaries.size(),
                (System.nanoTime() - start) / 1e9, out, Files.size(out) / 1024);
    }

    /**
     * Una localidad por línea. Cada anillo va como un polígono aparte de un MultiPolygon: {@link Localities} los lee
     * con la regla par-impar, como {@code Polygon}, así que un hueco también funciona.
     */
    static String geoJson(List<Locality> localities, List<OsmBoundaries.Boundary> boundaries, String extract) {
        StringBuilder json = new StringBuilder()
                .append("{\"type\":\"FeatureCollection\",\"source\":\"OpenStreetMap, límites de las localidades de ")
                .append("Bogotá, ").append(extract).append("; bordes simplificados a ")
                .append((int) ExtractSectors.SIMPLIFY_METERS)
                .append(" m\",\"license\":\"ODbL 1.0, © colaboradores de OpenStreetMap\",\"features\":[\n");
        for (int l = 0; l < localities.size(); l++) {
            Locality locality = localities.get(l);
            OsmBoundaries.Boundary boundary = boundaries.get(l);
            if (!boundary.ref().equals(Integer.toString(locality.number()))) {
                throw new IllegalStateException("la relación " + boundary.osmId() + " tiene ref " + boundary.ref()
                        + "; el catálogo dice " + locality.number() + " (" + locality.name() + ")");
            }
            json.append("{\"type\":\"Feature\",\"properties\":{\"ref\":\"").append(locality.number())
                    .append("\",\"name\":\"").append(locality.name()).append("\",\"osmRelation\":")
                    .append(boundary.osmId()).append("},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[");
            for (int r = 0; r < boundary.ringsLatE7().size(); r++) {
                int[] lat = boundary.ringsLatE7().get(r);
                int[] lon = boundary.ringsLonE7().get(r);
                boolean[] keep = Simplify.ring(lat, lon, ExtractSectors.SIMPLIFY_METERS);
                json.append(r == 0 ? "" : ",").append("[[");
                for (int i = 0; i < lat.length; i++) {
                    if (keep[i]) {
                        json.append(i == 0 ? "" : ",").append(ExtractSectors.point(lat[i], lon[i]));
                    }
                }
                json.append(',').append(ExtractSectors.point(lat[0], lon[0])).append("]]");
            }
            json.append("]}}").append(l + 1 < localities.size() ? ",\n" : "\n");
        }
        return json.append("]}\n").toString();
    }
}
