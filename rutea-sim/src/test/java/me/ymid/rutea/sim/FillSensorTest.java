package me.ymid.rutea.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class FillSensorTest {

    /** 1.100 L a 150 kg/m³ son 165 kg; con 137 kg al día se llena en 1,2 días. */
    private final FillSensor sensor = FillSensor.of(1_100, 150, 137);

    @Test
    void fillsAtTheDailyGenerationRate() {
        assertThat(sensor.capacityKg()).isCloseTo(165, within(1e-9));
        assertThat(sensor.fill(86_400)).isCloseTo(137 / 165.0, within(1e-9));
        assertThat(sensor.secondsToFull() / 86_400).isCloseTo(165 / 137.0, within(1e-9));
    }

    @Test
    void anEmptiedContainerStartsAtZeroAndCanOverflow() {
        assertThat(sensor.fill(-10)).isZero();
        assertThat(sensor.fill(2 * 86_400)).isGreaterThan(1);
    }

    @Test
    void readsInSteps() {
        assertThat(FillSensor.reading(0.83, 5)).isEqualTo(80);
        assertThat(FillSensor.reading(1.0, 5)).isEqualTo(100);
        assertThat(FillSensor.reading(1.66, 5)).isEqualTo(165);
    }
}
