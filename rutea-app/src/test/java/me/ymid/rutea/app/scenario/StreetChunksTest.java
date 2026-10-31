package me.ymid.rutea.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.SampleTown;

class StreetChunksTest {

    private static RoadGraph graph;
    private static IntPredicate town;

    @BeforeAll
    static void town(@TempDir Path dir) throws IOException {
        graph = SampleTown.graph(dir);
        town = AreaScenario.streets(graph, ServiceAreas.ASE_5, AreaScenarioFixture.SUBA_TOWN);
    }

    @Test
    void everyLocalStreetIsInExactlyOneChunk() {
        Set<Integer> local = new HashSet<>();
        for (int e = 0; e < graph.edgeCount(); e++) {
            int shape = base(graph.shape(e));
            if (SimulatedContainers.LOCAL_STREETS.contains(graph.roadClass(e)) && town.test(shape)) {
                local.add(shape);
            }
        }
        for (int max : new int[] {0, 150, 500, 5_000}) {
            List<Integer> covered = StreetChunks.of(graph, town, max).stream()
                    .flatMap(chunk -> Arrays.stream(chunk.arcs()).mapToObj(arc -> base(graph.shape(arc))))
                    .toList();
            assertThat(covered).as("trozo máx. %d m", max).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(local);
        }
    }

    @Test
    void aChunkIsAWalkTheTruckCanDrive() {
        for (StreetChunks.Chunk chunk : StreetChunks.of(graph, town, 500)) {
            int[] arcs = chunk.arcs();
            assertThat(graph.source(arcs[0])).isEqualTo(chunk.entry());
            assertThat(graph.target(arcs[arcs.length - 1])).isEqualTo(chunk.exit());
            int meters = 0;
            for (int k = 0; k < arcs.length; k++) {
                meters += graph.meters(arcs[k]);
                if (k > 0) {
                    assertThat(graph.source(arcs[k])).isEqualTo(graph.target(arcs[k - 1]));
                }
            }
            assertThat(chunk.meters()).isEqualTo(meters);
        }
    }

    @Test
    void aChunkNeverExceedsTheLimitUnlessOneStreetAlreadyDoes() {
        for (StreetChunks.Chunk chunk : StreetChunks.of(graph, town, 300)) {
            if (chunk.arcs().length > 1) {
                assertThat(chunk.meters()).isLessThanOrEqualTo(300);
            }
        }
        assertThat(StreetChunks.of(graph, town, 0)).allSatisfy(chunk -> assertThat(chunk.arcs()).hasSize(1));
    }

    @Test
    void longerChunksMeanFewerCustomers() {
        int previous = Integer.MAX_VALUE;
        for (int max : new int[] {0, 150, 500, 5_000}) {
            int count = StreetChunks.of(graph, town, max).size();
            assertThat(count).as("trozo máx. %d m", max).isLessThanOrEqualTo(previous);
            previous = count;
        }
    }

    @Test
    void rejectsANegativeLimit() {
        assertThatThrownBy(() -> StreetChunks.of(graph, town, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static int base(int shape) {
        return shape >= 0 ? shape : ~shape;
    }
}
