package me.ymid.rutea.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TrackTest {

    /** Sale a los 100 s, llega al punto 1 a los 200, para hasta los 260 y llega al punto 2 a los 300. */
    private static final Track TRACK = new Track(new double[] {0, 10, 10, 10}, new double[] {0, 0, 0, 20},
            new double[] {100, 200, 260, 300});

    @Test
    void interpolatesBetweenPointsAndWaitsAtTheEnds() {
        assertThat(TRACK.at(150)).containsExactly(5, 0);
        assertThat(TRACK.at(280)).containsExactly(10, 10);
        assertThat(TRACK.at(0)).containsExactly(0, 0);
        assertThat(TRACK.at(999)).containsExactly(10, 20);
    }

    @Test
    void aRepeatedPointIsAStop() {
        assertThat(TRACK.stopped(150)).isFalse();
        assertThat(TRACK.stopped(230)).isTrue();
        assertThat(TRACK.stopped(50)).isTrue();
        assertThat(TRACK.stopped(400)).isTrue();
    }

    @Test
    void betweenTwoReportsGivesEveryCornerTurned() {
        assertThat(TRACK.between(150, 280)).containsExactly(
                new double[] {10, 0, 200}, new double[] {10, 0, 260}, new double[] {10, 10, 280});
        assertThat(TRACK.between(210, 220)).containsExactly(new double[] {10, 0, 220});
    }

    @Test
    void refusesTimeGoingBack() {
        assertThatThrownBy(() -> new Track(new double[] {0, 1}, new double[] {0, 1}, new double[] {5, 4}))
                .hasMessageContaining("retrocede");
    }
}
