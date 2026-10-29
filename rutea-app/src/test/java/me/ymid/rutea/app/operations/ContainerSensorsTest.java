package me.ymid.rutea.app.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.domain.WasteStream;

class ContainerSensorsTest {

    /** Con 6 recolecciones por semana, 175 kg por recolección son 150 kg al día; 1.000 L a 150 kg/m³ caben 150 kg: se llena en un día justo. */
    private static final Container BIN = new Container("N0001", WasteStream.NON_RECYCLABLE, 0, 0, 0, 0, 175);

    private static ContainerSensors sensors(int days) {
        return ContainerSensors.of(List.of(BIN), new int[] {3_600}, List.of(1_000), 6,
                new ContainerSensors.Config(150, days, 1));
    }

    @Test
    void aTypicalDayStartsWithYesterdaysCollectionAtTheSameHour() {
        ContainerSensors sensors = sensors(1);
        double[] visit = {3_600};

        assertThat(sensors.fill(0, visit, 0)).isCloseTo(1 - 3_600 / 86_400.0, within(1e-9));
        assertThat(sensors.fill(0, visit, 3_600)).isZero();
        assertThat(sensors.fullBetween(0, visit, Double.NEGATIVE_INFINITY, 7_200)).isEmpty();
    }

    @Test
    void onMondayItFilledUpOnSundayAndOverflowsBeforeTheShift() {
        ContainerSensors sensors = sensors(2);

        assertThat(sensors.fill(0, new double[] {3_600}, 0)).isGreaterThan(1);
        assertThat(sensors.fullBetween(0, new double[] {3_600}, Double.NEGATIVE_INFINITY, 7_200).getAsDouble())
                .isCloseTo(3_600 - 86_400, within(1e-6));
    }

    @Test
    void anExtraPickupEmptiesItAndTheNextOverflowIsADayLater() {
        ContainerSensors sensors = sensors(2);
        double[] visits = {600, 3_600};

        assertThat(sensors.fill(0, visits, 1_200)).isCloseTo(600 / 86_400.0, within(1e-9));
        assertThat(sensors.fullBetween(0, visits, 0, 80_000)).isEmpty();
    }

    @Test
    void eachContainerTakesOneOfTheGivenSizes() {
        ContainerSensors sensors = ContainerSensors.of(List.of(BIN, BIN, BIN, BIN), new int[4],
                List.of(1_100, 2_400, 3_200), 6, ContainerSensors.Config.typical(7));

        for (int i = 0; i < sensors.size(); i++) {
            assertThat(sensors.liters(i)).isIn(1_100, 2_400, 3_200);
        }
    }
}
