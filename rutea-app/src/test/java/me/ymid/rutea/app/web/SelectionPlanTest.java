package me.ymid.rutea.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.planning.CityPlanner;
import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.geo.SampleTown;

class SelectionPlanTest {

    @TempDir
    Path dir;

    @Test
    void anAreaWithoutContainersInTheSelectionStillAnswersWithItsSkippedShiftsAndDeclaredFleet() throws IOException {
        // Un contenedor del ASE 1 en Usme: con Sumapaz sola, los dos turnos del ASE 1 quedan sin nada que recoger.
        OperatorContainers.Container usme = new OperatorContainers.Container("AL0004", 45_236_436, -741_228_775, 1_100,
                "USME", "01420", "DIA");
        List<Locality> sumapaz = List.of(ServiceAreas.locality(20).orElseThrow());

        SelectionPlan plan = SelectionPlan.of(sumapaz, CityPlanner.plan(SampleTown.graph(dir),
                Map.of(1, List.of(usme)), List.of(), sumapaz, 1.0, 7, 10, false));

        assertThat(plan.localities()).containsExactly(20);
        assertThat(plan.areas()).singleElement().satisfies(area -> {
            assertThat(area.number()).isEqualTo(1);
            assertThat(area.localities()).containsExactly(20);
            assertThat(area.shifts()).isEmpty();
            assertThat(area.skipped()).containsExactly("Dia", "Noche");
            assertThat(area.kgPerDay()).isZero();
            assertThat(area.sideLoadingShare()).hasValue(0.0);
            assertThat(area.fleet().trucks()).isZero();
            assertThat(area.fleet().declared()).isEqualTo(OptionalInt.of(3));
        });
    }
}
