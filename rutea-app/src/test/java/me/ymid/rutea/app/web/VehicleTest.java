package me.ymid.rutea.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.sim.Track;

class VehicleTest {

    private static final double[] DUMP = {9, 9};

    /** Dos contenedores en el mismo punto (100 y 160 s), la descarga (300 a 900 s) y otro contenedor (1.000 s). */
    private static final Vehicle TRUCK = new Vehicle("C-01", WasteStream.NON_RECYCLABLE, new Track(
            new double[] {0, 1, 1, 1, 9, 9, 2, 2}, new double[] {0, 1, 1, 1, 9, 9, 2, 2},
            new double[] {0, 100, 160, 220, 300, 900, 1000, 1060}), List.of("N1", "N2", "N3"), Vehicle.NOT_BROKEN);

    @Test
    void eachRepeatedPointOffTheDumpIsAContainerInOrder() {
        assertThat(TRUCK.arrivals(List.of(DUMP))).containsExactly(100.0, 160.0, 1000.0);
    }

    @Test
    void aBrokenTruckOnlyEmptiesWhatItReachedBeforeBreakingDown() {
        assertThat(TRUCK.brokenAt(500).arrivals(List.of(DUMP))).containsExactly(100.0, 160.0);
    }

    @Test
    void refusesATrackThatDoesNotMatchItsStops() {
        Vehicle wrong = new Vehicle("C-02", WasteStream.NON_RECYCLABLE, TRUCK.track(), List.of("N1"),
                Vehicle.NOT_BROKEN);

        assertThatThrownBy(() -> wrong.arrivals(List.of(DUMP))).hasMessageContaining("3 paradas");
    }
}
