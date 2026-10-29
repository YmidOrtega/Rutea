package me.ymid.rutea.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.geo.osm.PbfFixture;
import me.ymid.rutea.geo.osm.PbfFixture.Block;
import me.ymid.rutea.geo.osm.PbfFixture.Compression;

class OsmBoundariesTest {

    private static final int NODE = 0;
    private static final int WAY = 1;
    private static final int RELATION = 2;

    @TempDir
    Path dir;

    /**
     * Dos UPZ lado a lado que comparten el borde 2-5. La de la izquierda se arma con dos vías, una al revés; la de
     * la derecha, con una vía cerrada.
     */
    private Path twoUpz(Block group) throws IOException {
        return new PbfFixture().standardHeader()
                .data(new Block().denseNodes(new long[] {1, 2, 3, 4, 5, 6},
                        nano(4.70, 4.70, 4.70, 4.71, 4.71, 4.71), nano(-74.10, -74.09, -74.08, -74.10, -74.09, -74.08)),
                        Compression.ZLIB)
                .data(new Block().way(10, new long[] {1, 2, 5}).way(11, new long[] {1, 4, 5})
                        .way(12, new long[] {2, 3, 6, 5, 2}), Compression.ZLIB)
                .data(new Block()
                        .relation(100, new int[] {WAY, WAY}, new long[] {10, 11}, new String[] {"outer", "outer"},
                                "type", "boundary", "admin_level", "9", "name", "UPZ Oeste", "ref", "1")
                        .relation(101, new int[] {WAY}, new long[] {12}, new String[] {"outer"},
                                "type", "boundary", "admin_level", "9", "name", "UPZ Este", "ref", "2"),
                        Compression.ZLIB)
                .data(group, Compression.ZLIB)
                .write(dir);
    }

    @Test
    void readsEverySubareaWithItsClosedRings() throws IOException {
        Path pbf = twoUpz(new Block().relation(200, new int[] {NODE, RELATION, RELATION}, new long[] {1, 101, 100},
                new String[] {"label", "subarea", "subarea"}, "type", "boundary", "name", "UPZs de la localidad"));

        List<OsmBoundaries.Boundary> upz = OsmBoundaries.subareas(pbf, 200);

        assertThat(upz).extracting(OsmBoundaries.Boundary::name).containsExactly("UPZ Este", "UPZ Oeste");
        assertThat(upz).extracting(OsmBoundaries.Boundary::ref).containsExactly("2", "1");
        assertThat(upz.get(1).ringsLatE7()).singleElement().satisfies(ring -> assertThat(ring).hasSize(4));
        assertThat(upz.get(1).polygon().contains(47_050_000, -740_950_000)).isTrue();
        assertThat(upz.get(1).polygon().contains(47_050_000, -740_850_000)).isFalse();
        assertThat(upz.get(0).polygon().contains(47_050_000, -740_850_000)).isTrue();
    }

    @Test
    void readsBoundariesByIdWithoutAParent() throws IOException {
        Path pbf = twoUpz(new Block());

        assertThat(OsmBoundaries.of(pbf, List.of(100L, 101L))).extracting(OsmBoundaries.Boundary::ref)
                .containsExactly("1", "2");
        assertThatThrownBy(() -> OsmBoundaries.of(pbf, List.of(100L, 999L))).hasMessageContaining("no está el límite 999");
    }

    @Test
    void saysWhichRelationIsMissing() throws IOException {
        Path pbf = twoUpz(new Block().relation(200, new int[] {RELATION}, new long[] {999}, new String[] {"subarea"},
                "type", "boundary", "name", "UPZs de la localidad"));

        assertThatThrownBy(() -> OsmBoundaries.subareas(pbf, 300)).hasMessageContaining("no está la relación 300");
        assertThatThrownBy(() -> OsmBoundaries.subareas(pbf, 200)).hasMessageContaining("falta la subárea 999");
    }

    private static long[] nano(double... degrees) {
        long[] values = new long[degrees.length];
        for (int i = 0; i < degrees.length; i++) {
            values[i] = Math.round(degrees[i] * 1e9);
        }
        return values;
    }
}
