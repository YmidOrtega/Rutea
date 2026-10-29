package me.ymid.rutea.app.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.SampleTown;

class CityPlannerTest {

    @TempDir
    Path dir;

    /** Un contenedor del extracto del ASE 1, en Usme. */
    private static final OperatorContainers.Container USME = new OperatorContainers.Container("AL0004", 45_236_436,
            -741_228_775, 1_100, "USME", "01420", "DIA");

    @Test
    void aShiftWithoutContainersInTheSelectionIsSkippedNotSolved() throws IOException {
        RoadGraph town = SampleTown.graph(dir);

        CityPlanner.Plan plan = CityPlanner.plan(town, Map.of(1, List.of(USME)),
                List.of(ServiceAreas.locality(20).orElseThrow()), 1.0, 7, 10, true);

        assertThat(plan.areas()).isEmpty();
        assertThat(plan.skipped()).containsExactly(new CityPlanner.Skipped(ServiceAreas.ASE_1, SideLoading.ASE_1_DAY),
                new CityPlanner.Skipped(ServiceAreas.ASE_1, SideLoading.ASE_1_NIGHT));
    }

    @Test
    void shiftsThatDoNotOverlapShareTheirTrucks() {
        assertThat(CityPlanner.Fleet.overlap(SideLoading.ASE_2_DAY, SideLoading.ASE_2_NIGHT)).isFalse();
        assertThat(CityPlanner.Fleet.overlap(SideLoading.ASE_1_NIGHT, SideLoading.ASE_1_DAY)).isFalse();
        // 18:30–04:30 se cruza con uno que empieza a las 04:00.
        assertThat(CityPlanner.Fleet.overlap(SideLoading.ASE_2_NIGHT, new SideLoading.Shift("Madrugada", "", "04:00",
                3600))).isTrue();

        CityPlanner.Fleet lime = CityPlanner.Fleet.of(ServiceAreas.ASE_2,
                List.of(SideLoading.ASE_2_DAY, SideLoading.ASE_2_NIGHT), List.of(schedule(8, 7), schedule(4, 4)));

        assertThat(lime.trucks()).isEqualTo(8);
        assertThat(lime.lowerBound()).isEqualTo(7);
        assertThat(lime.beyondDeclared()).isEqualTo(3);
        assertThat(lime.warning()).hasValue("ASE 2: el plan pide 8 camiones de cargue lateral y LIME declara 5");
    }

    @Test
    void overlappingShiftsAddTheirTrucksAndAnUndeclaredFleetNeverWarns() {
        SideLoading.Shift late = new SideLoading.Shift("Tarde", "", "14:00", 9 * 3600);

        CityPlanner.Fleet fleet = CityPlanner.Fleet.of(ServiceAreas.ASE_4, List.of(SideLoading.ASE_4_DAY, late),
                List.of(schedule(3, 3), schedule(2, 2)));

        assertThat(fleet.trucks()).isEqualTo(5);
        assertThat(fleet.sharedAcrossShifts()).isFalse();
        assertThat(fleet.warning()).isEmpty();
    }

    private static ShiftScheduler.Schedule schedule(int trucks, int lowerBound) {
        List<ShiftScheduler.Truck> list = new ArrayList<>();
        for (int t = 0; t < trucks; t++) {
            list.add(new ShiftScheduler.Truck(new int[] {t}, new boolean[1], 0, 0));
        }
        return new ShiftScheduler.Schedule(list, 2_400, 10 * 3600, lowerBound);
    }

    @Test
    void anAreaWithRealContainersNeedsItsExtract() throws IOException {
        RoadGraph town = SampleTown.graph(dir);

        assertThatThrownBy(() -> CityPlanner.plan(town, Map.of(), List.of(ServiceAreas.locality(5).orElseThrow()),
                1.0, 7, 10, false)).hasMessageContaining("falta el extracto de contenedores de ASE 1");
    }
}
