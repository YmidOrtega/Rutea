package me.ymid.rutea.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoadGraphFileTest {

    private static final RoadGraph GRAPH = TestGraphs.of(3,
            new int[] {0, 1, 120, 20}, new int[] {1, 2, 80, 15}, new int[] {2, 0, 300, 40});
    private static final AreaSpec SPEC = new AreaSpec(1, 2, List.of(new AreaSpec.Site("base", 3, 500),
            new AreaSpec.Site("relleño", 4, 2000)));
    private static final byte[] SHA = sha(7);

    @TempDir
    Path dir;

    @Test
    void roundTripsEveryArray() throws IOException {
        Path file = dir.resolve("g.rgraph");
        RoadGraphFile.write(file, GRAPH, SHA, SPEC);

        RoadGraphFile.Stored stored = RoadGraphFile.read(file);
        RoadGraph read = stored.graph();

        assertThat(stored.sourceSha256()).isEqualTo(SHA);
        assertThat(stored.spec()).isEqualTo(SPEC);
        assertThat(read.osmNodeIds()).isEqualTo(GRAPH.osmNodeIds());
        assertThat(read.latE7()).isEqualTo(GRAPH.latE7());
        assertThat(read.lonE7()).isEqualTo(GRAPH.lonE7());
        assertThat(read.firstEdge()).isEqualTo(GRAPH.firstEdge());
        assertThat(read.edgeTarget()).isEqualTo(GRAPH.edgeTarget());
        assertThat(read.edgeMeters()).isEqualTo(GRAPH.edgeMeters());
        assertThat(read.edgeSeconds()).isEqualTo(GRAPH.edgeSeconds());
        assertThat(read.edgeRoadClass()).isEqualTo(GRAPH.edgeRoadClass());
        assertThat(read.edgeShape()).isEqualTo(GRAPH.edgeShape());
        assertThat(read.firstShapePoint()).isEqualTo(GRAPH.firstShapePoint());
        assertThat(read.shapeLatE7()).isEqualTo(GRAPH.shapeLatE7());
        assertThat(read.shapeLonE7()).isEqualTo(GRAPH.shapeLonE7());
        assertThat(read.shapeInLocality()).isEqualTo(GRAPH.shapeInLocality());
        assertThat(dir.resolve("g.rgraph.tmp")).doesNotExist();
    }

    @Test
    void treatsAnotherExtractOrCutAsStale() throws IOException {
        Path file = dir.resolve("g.rgraph");
        RoadGraphFile.write(file, GRAPH, SHA, SPEC);

        assertThat(RoadGraphFile.readIfCurrent(file, SHA, SPEC)).isPresent();
        assertThat(RoadGraphFile.readIfCurrent(file, sha(8), SPEC)).isEmpty();
        assertThat(RoadGraphFile.readIfCurrent(file, SHA, new AreaSpec(1, 2, List.of(new AreaSpec.Site("base", 3, 600)))))
                .isEmpty();
        assertThat(RoadGraphFile.readIfCurrent(dir.resolve("no-existe.rgraph"), SHA, SPEC)).isEmpty();
    }

    @Test
    void treatsAnotherFormatVersionAsStale() throws IOException {
        Path file = dir.resolve("g.rgraph");
        RoadGraphFile.write(file, GRAPH, SHA, SPEC);
        byte[] bytes = Files.readAllBytes(file);
        bytes[8] = (byte) (RoadGraphFile.VERSION + 1);
        Files.write(file, bytes);

        assertThat(RoadGraphFile.readIfCurrent(file, SHA, SPEC)).isEmpty();
    }

    @Test
    void detectsCorruptionWithTheChecksum() throws IOException {
        Path file = dir.resolve("g.rgraph");
        RoadGraphFile.write(file, GRAPH, SHA, SPEC);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 1;
        Files.write(file, bytes);

        assertThatThrownBy(() -> RoadGraphFile.read(file)).hasMessageContaining("CRC32 no coincide");
    }

    @Test
    void rejectsFilesThatAreNotGraphs() throws IOException {
        Path file = Files.writeString(dir.resolve("nota.rgraph"), "hola");

        assertThatThrownBy(() -> RoadGraphFile.read(file)).hasMessage("nota.rgraph: no es un grafo de Rutea");
    }

    private static byte[] sha(int fill) {
        byte[] sha = new byte[32];
        Arrays.fill(sha, (byte) fill);
        return sha;
    }
}
