package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import me.ymid.rutea.geo.OsmFeatures;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.geo.geometry.BoundingBox;

public final class ExtractSensitiveSites {

    private ExtractSensitiveSites() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("uso: ExtractSensitiveSites <extracto.osm.pbf> <grafo.rgraph> <salida.csv>");
            System.exit(2);
        }
        Path pbf = Path.of(args[0]);
        Path out = Path.of(args[2]);
        long start = System.nanoTime();
        BoundingBox box = localityBounds(RoadGraphFile.read(Path.of(args[1])).graph())
                .expand(SensitiveSites.FRONT_METERS);
        List<SensitiveSites.Site> sites = SensitiveSites.fromOsm(
                OsmFeatures.read(pbf, box, tags -> SensitiveSites.kindOf(tags).isPresent()));
        SensitiveSites.write(out, sites);
        Map<SensitiveSites.Kind, Integer> byKind = new TreeMap<>();
        sites.forEach(site -> byKind.merge(site.kind(), 1, Integer::sum));
        System.out.printf("%d sitios %s en %.1f s → %s (%d KB)%n", sites.size(), byKind,
                (System.nanoTime() - start) / 1e9, out, Files.size(out) / 1024);
    }

    /** Rectángulo de las calles de la localidad: los sitios más lejos no quedan frente a ningún contenedor. */
    static BoundingBox localityBounds(RoadGraph graph) {
        int minLat = Integer.MAX_VALUE;
        int maxLat = Integer.MIN_VALUE;
        int minLon = Integer.MAX_VALUE;
        int maxLon = Integer.MIN_VALUE;
        for (int s = 0; s < graph.shapeCount(); s++) {
            if (!graph.shapeInLocality(s)) {
                continue;
            }
            for (int p = graph.firstShapePoint(s); p < graph.endShapePoint(s); p++) {
                minLat = Math.min(minLat, graph.shapeLatE7(p));
                maxLat = Math.max(maxLat, graph.shapeLatE7(p));
                minLon = Math.min(minLon, graph.shapeLonE7(p));
                maxLon = Math.max(maxLon, graph.shapeLonE7(p));
            }
        }
        return new BoundingBox(minLat, minLon, maxLat, maxLon);
    }
}
