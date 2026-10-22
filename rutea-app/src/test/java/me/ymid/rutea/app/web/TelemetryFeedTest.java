package me.ymid.rutea.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import me.ymid.rutea.app.web.TelemetryMessage.Report;
import me.ymid.rutea.app.web.TelemetryMessage.VehicleState;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.sim.SimulationClock;
import me.ymid.rutea.sim.Track;

class TelemetryFeedTest {

    private static final double[] DUMP = {-74.1, 4.5};

    /** Sale a los 0 s, recoge en (1, 0) de 100 a 160, descarga en el relleno de 300 a 900 y vuelve a los 1000. */
    private static final Vehicle TRUCK = new Vehicle("C-01", WasteStream.NON_RECYCLABLE,
            new Track(new double[] {0, 1, 1, DUMP[0], DUMP[0], 0}, new double[] {0, 0, 0, DUMP[1], DUMP[1], 0},
                    new double[] {0, 100, 160, 300, 900, 1000}), List.of("N1"), Vehicle.NOT_BROKEN);

    private static final Vehicle RESERVE = new Vehicle("R-01-1", WasteStream.NON_RECYCLABLE,
            new Track(new double[] {0, 1}, new double[] {0, 1}, new double[] {500, 600}), List.of(),
            Vehicle.NOT_BROKEN);

    private final TelemetryFeed feed = new TelemetryFeed(List.of(DUMP));

    private static SimulationClock.State at(double seconds) {
        return new SimulationClock.State(seconds, 60, true, 1000);
    }

    private Report only(double seconds, Vehicle... vehicles) {
        return feed.next(List.of(vehicles), at(seconds), false).orElseThrow().vehicles().getFirst();
    }

    @Test
    void eachReportCarriesEveryPointPassedSinceTheLastOne() {
        only(50, TRUCK);

        Report report = only(120, TRUCK);

        assertThat(report.state()).isEqualTo(VehicleState.COLLECTING);
        assertThat(report.points()).containsExactly(new double[] {1, 0, 100}, new double[] {1, 0, 120});
    }

    @Test
    void namesWhatTheTruckIsDoing() {
        assertThat(only(50, TRUCK).state()).isEqualTo(VehicleState.DRIVING);
        assertThat(only(130, TRUCK).state()).isEqualTo(VehicleState.COLLECTING);
        assertThat(only(400, TRUCK).state()).isEqualTo(VehicleState.UNLOADING);
        assertThat(only(1000, TRUCK).state()).isEqualTo(VehicleState.DONE);
    }

    @Test
    void aStillTruckIsReportedOnlyWhenItsStateChanges() {
        only(400, TRUCK);

        assertThat(feed.next(List.of(TRUCK), at(500), false).orElseThrow().vehicles()).isEmpty();
        assertThat(feed.next(List.of(TRUCK), at(500), false)).isEmpty();
    }

    @Test
    void aBrokenTruckStaysWhereItBrokeDown() {
        Vehicle broken = TRUCK.brokenAt(50);

        Report report = only(400, broken);

        assertThat(report.state()).isEqualTo(VehicleState.BROKEN);
        assertThat(report.points().getLast()).containsExactly(0.5, 0, 50);
    }

    @Test
    void aReserveAppearsOnlyOnceDispatched() {
        assertThat(feed.next(List.of(RESERVE), at(400), false).orElseThrow().vehicles()).isEmpty();
        assertThat(only(550, RESERVE).points().getFirst()[2]).isEqualTo(500);
    }

    @Test
    void aSnapshotGivesTheLastFifteenMinutesOfTrail() {
        TelemetryMessage snapshot = feed.snapshot(List.of(TRUCK), at(1000));

        assertThat(snapshot.vehicles().getFirst().points().getFirst()[2]).isEqualTo(160);
        assertThat(snapshot.clock().running()).isTrue();
    }

    @Test
    void aNewShiftStartsTheTrailsOver() {
        only(400, TRUCK);

        Report report = only(50, TRUCK);

        assertThat(report.points().getFirst()[2]).isZero();
    }
}
