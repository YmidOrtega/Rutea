package me.ymid.rutea.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class UrbanPerimeterTest {

    @Test
    void overlappingPolygonsDoNotCancelEachOther() {
        UrbanPerimeter perimeter = new UrbanPerimeter(List.of(AreaScenarioFixture.rectangle(0, 0, 4, 4),
                AreaScenarioFixture.rectangle(2, 2, 6, 6)));

        assertThat(perimeter.contains(47_000_000 + 3 * 10_000, -740_000_000 + 3 * 10_000)).isTrue();
    }

    @Test
    void theCityCentreIsUrbanAndTheFarmlandOfUsmeIsNot() {
        UrbanPerimeter bogota = UrbanPerimeter.bogota();

        assertThat(bogota.contains(45_981_400, -740_760_400)).as("Plaza de Bolívar").isTrue();
        assertThat(bogota.contains(43_240_000, -742_030_000)).as("vía rural de Usme").isFalse();
    }
}
