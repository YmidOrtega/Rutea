package me.ymid.rutea.app.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.AreaScenarioFixture;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.app.scenario.SimulatedContainers;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.SampleTown;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

class CityPlannerTest {

    @TempDir
    Path dir;

    /** Un contenedor del extracto del ASE 1, en Usme. */
    private static final OperatorContainers.Container USME = new OperatorContainers.Container("AL0004", 45_236_436,
            -741_228_775, 1_100, "USME", "01420", "DIA");

    @Test
    void aShiftWithoutContainersInTheSelectionIsSkippedNotSolved() throws IOException {
        RoadGraph town = SampleTown.graph(dir);

        CityPlanner.Plan plan = CityPlanner.plan(town, Map.of(1, List.of(USME)), List.of(),
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
    @DisplayName("R02, R03, R04 y R07 · D. 1077/2015: cada plan de la selección sale con las cuatro revisadas")
    void everyPlanComesOutWithItsRulesChecked() throws IOException {
        AreaScenario town = AreaScenarioFixture.town(dir, 12, 400);
        SimulatedContainers.Container first = town.containers().getFirst();
        AreaScenario withSchool = town.withSites(List.of(new SensitiveSites.Site(SensitiveSites.Kind.EDUCATION,
                "node/1", "Colegio", new int[] {first.latE7()}, new int[] {first.lonE7()})));
        Solution solution = new AlnsSolver().solve(withSchool.instance(),
                new SolverSettings(7, Duration.ofSeconds(30), 50));

        DemoPlan plan = CityPlanner.checked(withSchool, solution,
                AreaPlanner.fewestTrucks(withSchool, solution, BogotaFigures.UNLOAD_SECONDS_LOW), 7, 50);

        assertThat(plan.compliance()).extracting(Compliance.Check::id).containsExactly("R02", "R03", "R04", "R07");
        assertThat(plan.compliance().get(3).count()).isGreaterThanOrEqualTo(1);
        assertThat(plan.containers().getFirst().noCompaction()).isTrue();
        // El pueblo de prueba queda fuera de las UPZ de Suba: todo es un solo sector, sin borde.
        assertThat(plan.sectors()).singleElement().satisfies(sector -> {
            assertThat(sector.ref()).isEqualTo(Sectors.RURAL);
            assertThat(sector.passes()).isNotEmpty();
        });
    }

    @Test
    void anAreaWithRealContainersNeedsItsExtract() throws IOException {
        RoadGraph town = SampleTown.graph(dir);

        assertThatThrownBy(() -> CityPlanner.plan(town, Map.of(), List.of(), List.of(ServiceAreas.locality(5).orElseThrow()),
                1.0, 7, 10, false)).hasMessageContaining("falta el extracto de contenedores de ASE 1");
    }
}
