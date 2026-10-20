package me.ymid.rutea.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.geo.geometry.Haversine;

/** Construye {@link SampleTown} y revisa el recorte, la compactación y la poda. */
class RoadGraphBuilderTest {

    private static final Map<Long, int[]> NODES = SampleTown.nodes();

    @TempDir
    static Path dir;

    private static RoadGraphBuilder.Result result;

    @BeforeAll
    static void build() throws IOException {
        result = RoadGraphBuilder.build(SampleTown.writePbf(dir), SampleTown.SPEC);
    }

    @Test
    void keepsIntersectionsAndEndpointsOfTheStronglyConnectedNetwork() {
        RoadGraph graph = result.graph();

        List<Long> vertices = new ArrayList<>();
        for (int v = 0; v < graph.vertexCount(); v++) {
            vertices.add(graph.osmNodeId(v));
        }

        assertThat(vertices).containsExactly(20L, 21L, 22L, 23L, 29L, 30L, 32L);
    }

    @Test
    void reportsWhatThePruningRemoved() {
        RoadGraphBuilder.Report report = result.report();

        assertThat(report.candidateWays()).as("sin footway ni access=private").isEqualTo(7);
        assertThat(report.includedWays()).as("sin la residencial fuera de la localidad").isEqualTo(6);
        assertThat(report.verticesBeforePruning()).isEqualTo(8);
        assertThat(report.localityVerticesBeforePruning()).isEqualTo(6);
        assertThat(report.edgesBeforePruning()).isEqualTo(15);
        assertThat(report.vertices()).isEqualTo(7);
        assertThat(report.localityVertices()).isEqualTo(5);
        assertThat(report.edges()).isEqualTo(14);
        assertThat(report.shapes()).isEqualTo(7);
        assertThat(result.graph().osmNodeId(report.destinationVertex())).isEqualTo(30);
    }

    @Test
    void twoWayStreetsShareOneShapeInOppositeDirections() {
        RoadGraph graph = result.graph();
        int a = vertexOf(20);
        int b = vertexOf(21);
        int ab = edge(a, b);
        int ba = edge(b, a);

        assertThat(graph.shape(ab)).isNotNegative();
        assertThat(graph.shape(ba)).isEqualTo(~graph.shape(ab));
        int shape = graph.shape(ab);
        List<Long> points = new ArrayList<>();
        for (int p = graph.firstShapePoint(shape); p < graph.endShapePoint(shape); p++) {
            points.add(pointId(graph.shapeLatE7(p), graph.shapeLonE7(p)));
        }
        assertThat(points).containsExactly(20L, 24L, 21L);
    }

    @Test
    void measuresEdgesAlongTheirGeometryWithClassSpeed() {
        RoadGraph graph = result.graph();
        int ab = edge(vertexOf(20), vertexOf(21));
        double meters = haversine(20, 24) + haversine(24, 21);

        assertThat(graph.meters(ab)).isEqualTo((int) Math.round(meters));
        assertThat(graph.seconds(ab)).isEqualTo((int) Math.round(graph.meters(ab) * 3.6 / 20));
        assertThat(graph.roadClass(ab)).isEqualTo(RoadClass.RESIDENTIAL);
        assertThat(graph.roadClass(edge(vertexOf(22), vertexOf(29)))).isEqualTo(RoadClass.PRIMARY);
        assertThat(graph.roadClass(edge(vertexOf(29), vertexOf(30)))).isEqualTo(RoadClass.SERVICE);
    }

    @Test
    void marksWhichShapesLieInsideTheLocality() {
        RoadGraph graph = result.graph();

        assertThat(graph.shapeInLocality(graph.shape(edge(vertexOf(20), vertexOf(21))))).isTrue();
        assertThat(graph.shapeInLocality(graph.shape(edge(vertexOf(29), vertexOf(30))))).isFalse();
    }

    @Test
    void findsTheSourceOfEveryEdge() {
        RoadGraph graph = result.graph();

        for (int v = 0; v < graph.vertexCount(); v++) {
            for (int e = graph.firstEdge(v); e < graph.endEdge(v); e++) {
                assertThat(graph.source(e)).isEqualTo(v);
            }
        }
    }

    @Test
    void everyVertexReachesEveryOther() {
        RoadGraph graph = result.graph();
        ShortestPaths paths = new ShortestPaths(graph, ShortestPaths.Weight.METERS);

        for (int s = 0; s < graph.vertexCount(); s++) {
            paths.run(s);
            for (int t = 0; t < graph.vertexCount(); t++) {
                assertThat(paths.distance(t)).as("%d → %d", s, t).isNotEqualTo(ShortestPaths.UNREACHABLE);
            }
        }
    }

    private static int vertexOf(long osmId) {
        RoadGraph graph = result.graph();
        for (int v = 0; v < graph.vertexCount(); v++) {
            if (graph.osmNodeId(v) == osmId) {
                return v;
            }
        }
        throw new AssertionError("no hay vértice para el nodo " + osmId);
    }

    private static int edge(int from, int to) {
        RoadGraph graph = result.graph();
        for (int e = graph.firstEdge(from); e < graph.endEdge(from); e++) {
            if (graph.target(e) == to) {
                return e;
            }
        }
        throw new AssertionError("no hay arco " + from + " → " + to);
    }

    private static long pointId(int lat, int lon) {
        return NODES.entrySet().stream().filter(n -> n.getValue()[0] == lat && n.getValue()[1] == lon)
                .findFirst().orElseThrow().getKey();
    }

    private static double haversine(long a, long b) {
        int[] p = NODES.get(a);
        int[] q = NODES.get(b);
        return Haversine.meters(p[0], p[1], q[0], q[1]);
    }
}
