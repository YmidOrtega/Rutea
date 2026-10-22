package me.ymid.rutea.geo.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SimplifyTest {

    // Un cuadrado de unos 1.100 m de lado en Suba, con un vértice sobrante a mitad de cada lado: dos casi en la
    // línea (a 2 m) y uno que se sale 30 m.
    private static final int[] LAT = {47_000_000, 47_050_000, 47_100_000, 47_100_000, 47_100_000, 47_050_000,
            47_000_000, 46_999_820};
    private static final int[] LON = {-741_000_000, -740_999_820, -741_000_000, -740_950_000, -740_900_000,
            -740_897_300, -740_900_000, -740_950_000};

    @Test
    void dropsVerticesCloserThanTheToleranceAndKeepsTheRest() {
        boolean[] keep = Simplify.ring(LAT, LON, 10);

        assertThat(keep).containsExactly(true, false, true, false, true, true, true, false);
    }

    @Test
    void aZeroToleranceKeepsEveryVertexOffTheLine() {
        boolean[] keep = Simplify.ring(LAT, LON, 0);

        assertThat(keep).containsExactly(true, true, true, false, true, true, true, true);
    }
}
