package me.ymid.rutea.app.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.sim.Track;

class VehicleTest {

    private static final Track TRACK = new Track(new double[] {0, 1, 2}, new double[] {0, 1, 2},
            new double[] {0, 100, 1000});

    /** Dos contenedores, un trozo de calle que recorre sin detenerse y otro contenedor. */
    private static final Vehicle TRUCK = new Vehicle("C-01", WasteStream.NON_RECYCLABLE, TRACK,
            List.of("N1", "N2", "T-1", "N3"), new int[] {100, 160, 400, 1000}, Vehicle.NOT_BROKEN);

    @Test
    void reachesEveryStopAtItsArrival() {
        assertThat(TRUCK.reached()).containsExactly(Map.entry("N1", 100.0), Map.entry("N2", 160.0),
                Map.entry("T-1", 400.0), Map.entry("N3", 1000.0));
    }

    @Test
    void aBrokenTruckOnlyReachesWhatItGotToBeforeBreakingDown() {
        assertThat(TRUCK.brokenAt(500).reached()).extracting(Map.Entry::getKey).containsExactly("N1", "N2", "T-1");
    }

    @Test
    void refusesArrivalsThatDoNotMatchItsStops() {
        assertThatThrownBy(() -> new Vehicle("C-02", WasteStream.NON_RECYCLABLE, TRACK, List.of("N1"),
                new int[] {100, 160}, Vehicle.NOT_BROKEN)).hasMessageContaining("2 horas de llegada para 1 paradas");
    }
}
