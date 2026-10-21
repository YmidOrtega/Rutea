package me.ymid.rutea.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.geo.geometry.Haversine;

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

        assertThat(vertices).containsExactly(20L, 21L, 22L, 23L, 24L, 29L, 30L, 32L);
    }

    @Test
    void reportsWhatThePruningRemoved() {
        RoadGraphBuilder.Report report = result.report();

        assertThat(report.candidateWays()).as("sin la peatonal; las privadas entran como candidatas").isEqualTo(8);
        assertThat(report.includedWays())
                .as("sin la residencial fuera de la localidad ni la privada lejos de un sitio").isEqualTo(6);
        assertThat(report.verticesBeforePruning()).isEqualTo(9);
        assertThat(report.localityVerticesBeforePruning()).isEqualTo(7);
        assertThat(report.edgesBeforePruning()).isEqualTo(17);
        assertThat(report.vertices()).isEqualTo(8);
        assertThat(report.localityVertices()).isEqualTo(6);
        assertThat(report.edges()).isEqualTo(16);
        assertThat(report.shapes()).isEqualTo(8);
        assertThat(result.graph().osmNodeId(report.siteVertices().get(AreaSpec.DISPOSAL))).isEqualTo(30);
        assertThat(result.graph().osmNodeId(report.siteVertices().get(AreaSpec.DEPOT))).isEqualTo(24);
    }

    @Test
    void twoWayStreetsShareOneShapeInOppositeDirections() {
        RoadGraph graph = result.graph();
        int c = vertexOf(22);
        int far = vertexOf(29);
        int ab = edge(c, far);
        int ba = edge(far, c);

        assertThat(graph.shape(ab)).isNotNegative();
        assertThat(graph.shape(ba)).isEqualTo(~graph.shape(ab));
        int shape = graph.shape(ab);
        List<Long> points = new ArrayList<>();
        for (int p = graph.firstShapePoint(shape); p < graph.endShapePoint(shape); p++) {
            points.add(pointId(graph.shapeLatE7(p), graph.shapeLonE7(p)));
        }
        assertThat(points).containsExactly(22L, 28L, 29L);
    }

    @Test
    void measuresEdgesAlongTheirGeometryWithClassSpeed() {
        RoadGraph graph = result.graph();
        int primary = edge(vertexOf(22), vertexOf(29));
        double meters = haversine(22, 28) + haversine(28, 29);

        assertThat(graph.meters(primary)).isEqualTo((int) Math.round(meters));
        assertThat(graph.seconds(primary)).isEqualTo((int) Math.round(graph.meters(primary) * 3.6 / 40));
        assertThat(graph.roadClass(primary)).isEqualTo(RoadClass.PRIMARY);
        assertThat(graph.roadClass(edge(vertexOf(20), vertexOf(24)))).isEqualTo(RoadClass.RESIDENTIAL);
        assertThat(graph.roadClass(edge(vertexOf(29), vertexOf(30)))).isEqualTo(RoadClass.SERVICE);
    }

    @Test
    void marksWhichShapesLieInsideTheLocality() {
        RoadGraph graph = result.graph();

        assertThat(graph.shapeInLocality(graph.shape(edge(vertexOf(20), vertexOf(24))))).isTrue();
        assertThat(graph.shapeInLocality(graph.shape(edge(vertexOf(29), vertexOf(30))))).isFalse();
    }

    @Test
    void splitsAStreetWhereASiteSits() {
        RoadGraph graph = result.graph();

        assertThat(graph.meters(edge(vertexOf(20), vertexOf(24)))).isEqualTo((int) Math.round(haversine(20, 24)));
        assertThat(graph.meters(edge(vertexOf(24), vertexOf(21)))).isEqualTo((int) Math.round(haversine(24, 21)));
        assertThat(graph.vertexOf(24)).hasValue(vertexOf(24));
        assertThat(graph.vertexOf(28)).isEmpty();
    }

    @Test
    void refusesASiteThatIsNotOnAnyIncludedRoad() {
        // El nodo 26 solo está en una vía peatonal.
        AreaSpec spec = new AreaSpec(100, 200, List.of(new AreaSpec.Site("peatonal", 26, 0)));

        assertThatThrownBy(() -> RoadGraphBuilder.build(SampleTown.writePbf(dir), spec))
                .hasMessageContaining("el sitio peatonal (nodo 26) no está sobre ninguna vía del recorte");
    }

    @Test
    void refusesASiteYouCanReachButNotLeave() {
        // El nodo 25 es el final del sentido único sin salida.
        AreaSpec spec = new AreaSpec(100, 200, List.of(new AreaSpec.Site("trampa", 25, 0)));

        assertThatThrownBy(() -> RoadGraphBuilder.build(SampleTown.writePbf(dir), spec))
                .hasMessageContaining("el sitio trampa (nodo 25) quedó fuera de la componente fuertemente conexa");
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
