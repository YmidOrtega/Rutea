package me.ymid.rutea.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.geo.geometry.BoundingBox;
import me.ymid.rutea.geo.osm.PbfFixture;
import me.ymid.rutea.geo.osm.PbfFixture.Block;
import me.ymid.rutea.geo.osm.PbfFixture.Compression;

class OsmFeaturesTest {

    @TempDir
    Path dir;

    @Test
    void readsTaggedNodesAndPolygonsInsideTheBox() throws IOException {
        // Nodos 1 y 2 con etiquetas; 3-6 son el polígono cerrado de un colegio; 7 queda fuera del rectángulo.
        Path pbf = new PbfFixture().standardHeader()
                .data(new Block().taggedDenseNodes(new long[] {1, 2, 3, 4, 5, 6, 7},
                        nano(4.700, 4.701, 4.702, 4.702, 4.703, 4.703, 4.900),
                        nano(-74.100, -74.101, -74.102, -74.103, -74.103, -74.102, -74.100),
                        new String[] {"amenity", "hospital", "name", "Hospital de Suba"},
                        new String[] {"amenity", "bench"},
                        new String[] {}, new String[] {}, new String[] {}, new String[] {},
                        new String[] {"amenity", "school", "name", "Afuera"}), Compression.ZLIB)
                .data(new Block().way(100, new long[] {3, 4, 5, 6, 3}, "amenity", "school", "name", "Colegio"),
                        Compression.ZLIB)
                .write(dir);
        BoundingBox box = new BoundingBox(46_990_000, -741_050_000, 47_050_000, -740_950_000);

        List<OsmFeatures.Feature> features = OsmFeatures.read(pbf, box,
                tags -> "hospital".equals(tags.get("amenity")) || "school".equals(tags.get("amenity")));

        assertThat(features).extracting(OsmFeatures.Feature::name).containsExactlyInAnyOrder("Hospital de Suba", "Colegio");
        OsmFeatures.Feature school = features.stream().filter(f -> f.osmType().equals("way")).findFirst().orElseThrow();
        assertThat(school.latE7()).as("sin repetir el nodo que cierra el polígono").hasSize(4);
        assertThat(school.osmId()).isEqualTo(100);
    }

    private static long[] nano(double... degrees) {
        long[] values = new long[degrees.length];
        for (int i = 0; i < degrees.length; i++) {
            values[i] = Math.round(degrees[i] * 1e9);
        }
        return values;
    }
}
