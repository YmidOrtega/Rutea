package me.ymid.rutea.geo.osm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import me.ymid.rutea.geo.osm.OsmRelation.Member;
import me.ymid.rutea.geo.osm.OsmRelation.MemberType;
import me.ymid.rutea.geo.osm.PbfFixture.Block;
import me.ymid.rutea.geo.osm.PbfFixture.Compression;
import me.ymid.rutea.geo.osm.PbfReader.Element;

class PbfReaderTest {

    private static final Set<Element> ALL = EnumSet.allOf(Element.class);

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(Compression.class)
    void readsDenseNodesInSubaWithNegativeLongitudes(Compression compression) throws IOException {
        Path file = new PbfFixture().standardHeader()
                .data(new Block().denseNodes(new long[] {1001, 1002, 990},
                        new long[] {4_741_000_000L, 4_741_000_100L, 4_756_123_400L},
                        new long[] {-74_084_000_000L, -74_083_999_900L, -74_101_000_000L}), compression)
                .write(dir);

        OsmBlock block = readAll(file, ALL).getFirst();

        assertThat(block.nodeIds()).containsExactly(1001, 1002, 990);
        assertThat(block.latE7()).containsExactly(47_410_000, 47_410_001, 47_561_234);
        assertThat(block.lonE7()).containsExactly(-740_840_000, -740_839_999, -741_010_000);
    }

    @Test
    void appliesGranularityAndOffsets() throws IOException {
        Path file = new PbfFixture().standardHeader()
                .data(new Block().coordinates(1000, 4_000_000_000L, -74_000_000_000L)
                        .denseNodes(new long[] {1}, new long[] {4_741_000_000L}, new long[] {-74_084_000_000L}),
                        Compression.ZLIB)
                .write(dir);

        OsmBlock block = readAll(file, ALL).getFirst();

        assertThat(block.latE7()).containsExactly(47_410_000);
        assertThat(block.lonE7()).containsExactly(-740_840_000);
    }

    @Test
    void roundsNanodegreesToTheNearestE7() throws IOException {
        Path file = new PbfFixture().standardHeader()
                .data(new Block().coordinates(1, 0, 0).denseNodes(new long[] {1, 2, 3},
                        new long[] {4_741_000_049L, 4_741_000_050L, 4_741_000_000L},
                        new long[] {-74_084_000_049L, -74_084_000_051L, -74_084_000_000L}), Compression.RAW)
                .write(dir);

        OsmBlock block = readAll(file, ALL).getFirst();

        assertThat(block.latE7()).containsExactly(47_410_000, 47_410_001, 47_410_000);
        assertThat(block.lonE7()).containsExactly(-740_840_000, -740_840_001, -740_840_000);
    }

    @Test
    void readsPlainNodesToo() throws IOException {
        Path file = new PbfFixture().standardHeader()
                .data(new Block().node(-7, 4_741_000_000L, -74_084_000_000L), Compression.ZLIB)
                .write(dir);

        OsmBlock block = readAll(file, ALL).getFirst();

        assertThat(block.nodeIds()).containsExactly(-7);
        assertThat(block.latE7()).containsExactly(47_410_000);
    }

    @Test
    void readsWaysWithTagsAndNodeOrder() throws IOException {
        Path file = new PbfFixture().standardHeader()
                .data(new Block().way(55, new long[] {10, 12, 11, 10},
                        "highway", "primary", "name", "Avenida Boyacá", "oneway", "yes"), Compression.ZLIB)
                .write(dir);

        OsmWay way = readAll(file, ALL).getFirst().ways().getFirst();

        assertThat(way.id()).isEqualTo(55);
        assertThat(way.nodeIds()).containsExactly(10, 12, 11, 10);
        assertThat(way.tags()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("highway", "primary", "name", "Avenida Boyacá", "oneway", "yes"));
    }

    @Test
    void readsRelationMembersWithTypesAndRoles() throws IOException {
        Path file = new PbfFixture().standardHeader()
                .data(new Block().relation(9, new int[] {1, 1, 0}, new long[] {300, 200, 5},
                        new String[] {"outer", "outer", "admin_centre"},
                        "boundary", "administrative", "name", "Suba"), Compression.ZLIB)
                .write(dir);

        OsmRelation relation = readAll(file, ALL).getFirst().relations().getFirst();

        assertThat(relation.members()).containsExactly(
                new Member(MemberType.WAY, 300, "outer"),
                new Member(MemberType.WAY, 200, "outer"),
                new Member(MemberType.NODE, 5, "admin_centre"));
        assertThat(relation.tags()).containsEntry("name", "Suba");
    }

    @Test
    void decodesOnlyTheRequestedElements() throws IOException {
        Path file = new PbfFixture().standardHeader()
                .data(new Block().denseNodes(new long[] {1}, new long[] {0}, new long[] {0})
                        .way(2, new long[] {1}, "highway", "residential")
                        .relation(3, new int[] {1}, new long[] {2}, new String[] {"outer"}), Compression.ZLIB)
                .write(dir);

        OsmBlock block = readAll(file, EnumSet.of(Element.WAYS)).getFirst();

        assertThat(block.nodeCount()).isZero();
        assertThat(block.ways()).hasSize(1);
        assertThat(block.relations()).isEmpty();
    }

    @Test
    void deliversBlocksInFileOrderWhateverTheThreadCount() throws IOException {
        PbfFixture fixture = new PbfFixture().standardHeader();
        for (int i = 0; i < 64; i++) {
            fixture.data(new Block().denseNodes(new long[] {i}, new long[] {0}, new long[] {0}), Compression.ZLIB);
        }
        Path file = fixture.write(dir);

        for (int threads : new int[] {1, 3, 8}) {
            List<Long> ids = new ArrayList<>();
            PbfReader.read(file, ALL, threads, block -> ids.add(block.nodeIds()[0]));

            assertThat(ids).as("%d hilos", threads).hasSize(64).isSorted();
        }
    }

    @Test
    void ignoresUnknownBlobTypes() throws IOException {
        Path file = new PbfFixture().standardHeader()
                .blob("ExtensionDeAlguien", new byte[] {1, 2, 3})
                .data(new Block().way(1, new long[] {1, 2}), Compression.RAW)
                .write(dir);

        assertThat(readAll(file, ALL)).hasSize(1);
    }

    @Test
    void rejectsRequiredFeaturesItCannotHonor() throws IOException {
        Path file = new PbfFixture().header("OsmSchema-V0.6", "DenseNodes", "HistoricalInformation").write(dir);

        assertThatThrownBy(() -> readAll(file, ALL)).isInstanceOf(PbfFormatException.class)
                .hasMessage("fixture.osm.pbf: bloque 0: el archivo exige características no soportadas: "
                        + "[HistoricalInformation]");
    }

    @Test
    void rejectsDataBeforeTheHeader() throws IOException {
        Path file = new PbfFixture().data(new Block(), Compression.ZLIB).write(dir);

        assertThatThrownBy(() -> readAll(file, ALL)).hasMessageContaining("OSMData antes de OSMHeader");
    }

    @Test
    void rejectsFileWithoutHeader() throws IOException {
        Path file = Files.write(dir.resolve("vacio.osm.pbf"), new byte[0]);

        assertThatThrownBy(() -> readAll(file, ALL)).hasMessage("vacio.osm.pbf: el archivo no tiene OSMHeader");
    }

    @Test
    void reportsTruncatedFileWithTheBlockNumber() throws IOException {
        byte[] whole = new PbfFixture().standardHeader()
                .data(new Block().way(1, new long[] {1, 2}), Compression.ZLIB).toByteArray();
        Path file = Files.write(dir.resolve("cortado.osm.pbf"), java.util.Arrays.copyOf(whole, whole.length - 5));

        assertThatThrownBy(() -> readAll(file, ALL)).isInstanceOf(PbfFormatException.class)
                .hasMessageStartingWith("cortado.osm.pbf: bloque 1: archivo cortado; faltan 5 de");
    }

    @Test
    void rejectsGarbageThatIsNotAPbf() throws IOException {
        Path file = Files.writeString(dir.resolve("mapa.osm"), "<?xml version='1.0'?><osm/>");

        assertThatThrownBy(() -> readAll(file, ALL)).isInstanceOf(PbfFormatException.class)
                .hasMessageContaining("el máximo es 65536");
    }

    @Test
    void rejectsCompressionsGeofabrikDoesNotUse() throws IOException {
        Path file = new PbfFixture().blob("OSMHeader", new ProtoWriter().bytes(7, new byte[] {1}).toByteArray())
                .write(dir);

        assertThatThrownBy(() -> readAll(file, ALL)).hasMessageContaining("compresión Zstandard no soportada");
    }

    @Test
    void rejectsZlibThatInflatesToTheWrongSize() throws IOException {
        byte[] good = PbfFixture.blobMessage(new ProtoWriter().string(4, "DenseNodes").toByteArray(),
                Compression.ZLIB);
        ProtoReader reader = new ProtoReader(good);
        reader.next();
        long rawSize = reader.varint();
        reader.next();
        byte[] zlib = reader.bytes();
        byte[] lying = new ProtoWriter().varint(2, rawSize + 10).bytes(3, zlib).toByteArray();
        Path file = new PbfFixture().blob("OSMHeader", lying).write(dir);

        assertThatThrownBy(() -> readAll(file, ALL)).hasMessageContaining("se esperaban " + (rawSize + 10));
    }

    @Test
    void rejectsStringIndexOutsideTheTable() throws IOException {
        ProtoWriter way = new ProtoWriter().varint(1, 8).packed(2, 40).packed(3, 0);
        Path file = new PbfFixture().standardHeader()
                .data(new Block().group(new ProtoWriter().message(3, way)), Compression.RAW)
                .write(dir);

        assertThatThrownBy(() -> readAll(file, ALL)).isInstanceOf(PbfFormatException.class)
                .hasMessage("fixture.osm.pbf: bloque 1: vía 8: índice 40 fuera de la tabla de 1 cadenas");
    }

    private static List<OsmBlock> readAll(Path file, Set<Element> elements) throws IOException {
        List<OsmBlock> blocks = new ArrayList<>();
        PbfReader.read(file, elements, 2, blocks::add);
        return blocks;
    }
}
