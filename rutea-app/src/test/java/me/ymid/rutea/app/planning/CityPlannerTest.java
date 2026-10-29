package me.ymid.rutea.app.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
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
    void anAreaWithRealContainersNeedsItsExtract() throws IOException {
        RoadGraph town = SampleTown.graph(dir);

        assertThatThrownBy(() -> CityPlanner.plan(town, Map.of(), List.of(ServiceAreas.locality(5).orElseThrow()),
                1.0, 7, 10, false)).hasMessageContaining("falta el extracto de contenedores de ASE 1");
    }
}
