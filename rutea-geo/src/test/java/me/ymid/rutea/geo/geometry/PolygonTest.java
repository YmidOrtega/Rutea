package me.ymid.rutea.geo.geometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.List;

import org.junit.jupiter.api.Test;

class PolygonTest {

    @Test
    void assemblesRingsFromWaysInAnyOrderAndDirection() {
        List<long[]> rings = Polygon.assembleRings(List.of(
                new long[] {3, 4}, new long[] {1, 2}, new long[] {1, 4}, new long[] {3, 2}, new long[] {7, 8, 9, 7}));

        assertThat(rings).hasSize(2);
        assertThat(rings.get(0)).containsExactly(7, 8, 9, 7);
        assertThat(rings.get(1)).hasSize(5).startsWith(rings.get(1)[4]);
        assertThat(rings.get(1)).contains(1, 2, 3, 4);
    }

    @Test
    void rejectsAnOpenBoundary() {
        assertThatThrownBy(() -> Polygon.assembleRings(List.<long[]>of(new long[] {1, 2, 3})))
                .hasMessageContaining("anillo abierto");
    }

    @Test
    void containsUsesEvenOddSoInnerRingsAreHoles() {
        Polygon polygon = new Polygon(
                List.of(new int[] {0, 0, 100, 100}, new int[] {40, 40, 60, 60}),
                List.of(new int[] {0, 100, 100, 0}, new int[] {40, 60, 60, 40}));

        assertThat(polygon.contains(10, 10)).isTrue();
        assertThat(polygon.contains(50, 50)).as("dentro del hueco").isFalse();
        assertThat(polygon.contains(150, 50)).isFalse();
        assertThat(polygon.contains(-74, -740_000_000)).isFalse();
    }

    @Test
    void worksWithNegativeLongitudesLikeBogota() {
        Polygon square = new Polygon(List.of(new int[] {47_000_000, 47_000_000, 47_100_000, 47_100_000}),
                List.of(new int[] {-741_000_000, -740_000_000, -740_000_000, -741_000_000}));

        assertThat(square.contains(47_050_000, -740_500_000)).isTrue();
        assertThat(square.contains(47_050_000, -739_900_000)).isFalse();
    }

    @Test
    void haversineMatchesOneDegreeOfLatitude() {
        double meters = Haversine.meters(0, 0, 10_000_000, 0);

        assertThat(meters).isCloseTo(Haversine.EARTH_RADIUS_METERS * Math.PI / 180, offset(1e-6));
        assertThat(meters).isBetween(111_194.0, 111_196.0);
    }

    @Test
    void expandedBoxCoversTheMarginInEveryDirection() {
        BoundingBox box = new BoundingBox(47_000_000, -741_000_000, 47_100_000, -740_000_000).expand(500);

        assertThat(Haversine.meters(47_000_000, -741_000_000, box.minLatE7(), -741_000_000)).isGreaterThanOrEqualTo(500);
        assertThat(Haversine.meters(47_100_000, -740_000_000, 47_100_000, box.maxLonE7())).isGreaterThanOrEqualTo(500);
    }
}
