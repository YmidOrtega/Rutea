package me.ymid.rutea.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class RoadGraphTest {

    /** 0 ─ 1 ─ 2 ─ 3 de doble sentido; las calles son los trazos 0, 1 y 2. */
    private static final RoadGraph LINE = TestGraphs.streets(4,
            new int[] {0, 1, 100, 10}, new int[] {1, 2, 100, 10}, new int[] {2, 3, 100, 10});

    @Test
    void closingAStreetRemovesItsArcsInBothDirections() {
        RoadGraph closed = LINE.closing(1);

        assertThat(LINE.edgesOn(1)).hasSize(2);
        assertThat(closed.edgesOn(1)).isEmpty();
        assertThat(closed.edgeCount()).isEqualTo(LINE.edgeCount() - 2);
        assertThat(new ShortestPaths(closed, ShortestPaths.Weight.METERS).distances(0, new int[] {1, 2}))
                .containsExactly(100, ShortestPaths.UNREACHABLE);
    }

    @Test
    void closingKeepsVerticesAndShapesSoTheirIndicesStillHold() {
        RoadGraph closed = LINE.closing(0, 2);

        assertThat(closed.vertexCount()).isEqualTo(LINE.vertexCount());
        assertThat(closed.shapeCount()).isEqualTo(LINE.shapeCount());
        assertThat(Arrays.stream(closed.edgesOn(1)).map(closed::shape)).containsExactlyInAnyOrder(1, ~1);
    }

    @Test
    void reversedGraphFlipsEveryArcOverTheSameShape() {
        RoadGraph oneWay = TestGraphs.of(3, new int[] {0, 1, 100, 10}, new int[] {1, 2, 200, 20});

        RoadGraph reversed = oneWay.reversed();

        assertThat(reversed.edgeCount()).isEqualTo(2);
        int back = reversed.firstEdge(2);
        assertThat(reversed.endEdge(2) - back).isEqualTo(1);
        assertThat(reversed.target(back)).isEqualTo(1);
        assertThat(reversed.meters(back)).isEqualTo(200);
        assertThat(reversed.shape(back)).isEqualTo(~oneWay.shape(oneWay.firstEdge(1)));
        assertThat(reversed.reversed()).isSameAs(oneWay);
    }

    @Test
    void nearestShapeIsTheStreetUnderThePoint() {
        // Los vértices van hacia el norte cada 0,001°; un punto a mitad entre 2 y 3 cae en la calle 2.
        int latE7 = (LINE.latE7(2) + LINE.latE7(3)) / 2;

        assertThat(LINE.nearestShape(latE7, LINE.lonE7(2) + 2_000)).isEqualTo(2);
    }

    @Test
    void refusesArcsShorterThanAMeter() {
        assertThatThrownBy(() -> TestGraphs.of(2, new int[] {0, 1, 0, 5}, new int[] {1, 0, 10, 5}))
                .hasMessageContaining("mide 0 m");
    }
}
