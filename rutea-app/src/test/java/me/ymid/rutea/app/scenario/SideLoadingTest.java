package me.ymid.rutea.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.util.List;

import org.junit.jupiter.api.Test;

class SideLoadingTest {

    @Test
    void aContainerCarriesItsLitersAtLooseDensityTimesTheFill() {
        assertThat(SideLoading.kgAtCollection(2_200, 1.0)).isEqualTo(330);
        assertThat(SideLoading.kgAtCollection(2_400, 0.75)).isEqualTo(270);
        assertThat(SideLoading.kgAtCollection(1_100, 0.5)).isEqualTo(83);
        assertThatThrownBy(() -> SideLoading.kgAtCollection(0, 1.0)).hasMessageContaining("positivos");
        assertThatThrownBy(() -> SideLoading.kgAtCollection(1_100, 0)).hasMessageContaining("positivos");
    }

    @Test
    void theLoadOfEachCollectionIsSpreadOverTheWeek() {
        assertThat(SideLoading.kgPerDay(List.of(2_200, 2_200), 3, 1.0)).isCloseTo(660 * 3 / 7.0, offset(1e-9));
    }

    @Test
    void anAssumedFrequencyIsAlsoMeasuredAtSixTimesAWeek() {
        assertThat(SideLoading.AREAS).extracting(area -> area.area().number()).containsExactly(1, 2, 3, 4, 5);
        assertThat(SideLoading.of(ServiceAreas.ASE_2).frequencies()).containsExactly(3, 6);
        assertThat(SideLoading.of(ServiceAreas.ASE_1).frequencies()).containsExactly(3);
        assertThat(SideLoading.of(ServiceAreas.ASE_5).frequencies())
                .containsExactly(ServiceAreas.ASE_5.verified().collectionsPerWeek());
    }

    @Test
    void theShareNeedsTheTonnesOfTheArea() {
        assertThat(SideLoading.of(ServiceAreas.ASE_4).share(72_510)).hasValueSatisfying(
                share -> assertThat(share).isCloseTo(0.1, offset(1e-9)));
        assertThat(SideLoading.of(ServiceAreas.ASE_5).share(72_510)).isEmpty();
    }

    @Test
    void sideLoadingShiftsChainFourTripsAndSubaKeepsThree() {
        SideLoading.Area lime = SideLoading.of(ServiceAreas.ASE_2);

        assertThat(lime.shifts()).containsExactly(SideLoading.ASE_2_DAY, SideLoading.ASE_2_NIGHT);
        assertThat(lime.operation(SideLoading.ASE_2_NIGHT).maxTripsPerTruck()).isEqualTo(4);
        assertThat(lime.operation(SideLoading.ASE_2_NIGHT).compactorKg()).isEqualTo(SideLoading.SIDE_LOADER_KG);
        assertThat(ServiceAreas.ASE_5.verified().maxTripsPerTruck()).isEqualTo(3);
        assertThatThrownBy(() -> SideLoading.of(ServiceAreas.ASE_1).operation(SideLoading.ASE_4_DAY))
                .hasMessageContaining("no es un turno");
    }

    @Test
    void everyAreaHasAShiftAndEitherRealOrPlannedContainers() {
        List<Integer> real = OperatorContainers.LAYERS.stream().map(OperatorContainers.Layer::area).toList();

        assertThat(SideLoading.AREAS).allSatisfy(area -> {
            assertThat(area.shifts()).isNotEmpty();
            assertThat(area.planned().isPresent()).isNotEqualTo(real.contains(area.area().number()));
        });
        assertThat(SideLoading.of(ServiceAreas.ASE_3).planned()).hasValue(new SideLoading.Planned(91, List.of(1_100)));
    }

    @Test
    void plansUseTheHighestMeasuredFill() {
        assertThat(SideLoading.FILLS).contains(SideLoading.MEAN_FILL);
        assertThat(SideLoading.MEAN_FILL).isEqualTo(SideLoading.FILLS.stream().mapToDouble(Double::doubleValue).max()
                .orElseThrow());
    }
}
