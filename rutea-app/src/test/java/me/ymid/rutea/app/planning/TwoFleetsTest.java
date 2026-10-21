package me.ymid.rutea.app.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.planning.Compliance.Status;
import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.app.scenario.SubaScenarioFixture;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

class TwoFleetsTest {

    @TempDir
    static Path dir;

    private static SubaScenario waste;
    private static SubaScenario recyclables;
    private static DemoPlan merged;

    @BeforeAll
    static void plan() throws IOException {
        waste = SubaScenarioFixture.town(dir, 12, 1_600);
        SubaScenario plainRecyclables = SubaScenarioFixture.recyclableTown(dir, 12, 300);
        Container first = plainRecyclables.containers().getFirst();
        // Un colegio encima del primer contenedor blanco: sin compactación, R07 no tiene nada que marcar.
        recyclables = plainRecyclables.withSites(List.of(new SensitiveSites.Site(SensitiveSites.Kind.EDUCATION,
                "node/1", "Colegio", new int[] {first.latE7()}, new int[] {first.lonE7()})));
        DemoPlan a = plan(waste, SubaFigures.UNLOAD_SECONDS_LOW);
        DemoPlan b = plan(recyclables, SubaFigures.ECA_UNLOAD_SECONDS);
        merged = SubaPlanner.merge(a, b);
    }

    private static DemoPlan plan(SubaScenario scenario, int unload) {
        Solution solution = new AlnsSolver().solve(scenario.instance(), new SolverSettings(1, Duration.ofMinutes(1), 200));
        ShiftScheduler.Schedule schedule = ShiftScheduler.schedule(scenario, solution, 60, unload, 8 * 3600, 3);
        return SubaPlanner.plan(scenario, solution, schedule, "alns", 1, 200);
    }

    @Test
    @DisplayName("R10 · los aprovechables salen de la ECA y vuelven a ella")
    void recyclablesLeaveFromAndReturnToTheEca() {
        assertThat(recyclables.depotVertex()).isEqualTo(recyclables.gateVertex());
        assertThat(recyclables.returnMeters()).isZero();
        assertThat(recyclables.stream()).isEqualTo(WasteStream.RECYCLABLE);
        assertThat(recyclables.instance().capacity()).isEqualTo(SubaFigures.RECYCLABLE_VEHICLE_KG);
        assertThat(merged.routes()).filteredOn(r -> r.stream() == WasteStream.RECYCLABLE)
                .allSatisfy(r -> assertThat(r.id()).startsWith("A-"));
        assertThat(merged.routes()).filteredOn(r -> r.stream() == WasteStream.NON_RECYCLABLE)
                .allSatisfy(r -> assertThat(r.id()).startsWith("C-"));
        assertThat(merged.sites()).extracting(DemoPlan.Site::id).containsExactly("relleno", "base", "eca");
    }

    @Test
    @DisplayName("R06 · R07 no aplica a un vehículo sin compactación")
    void noCompactionRuleSkipsVehiclesThatDoNotCompact() {
        assertThat(merged.containers()).filteredOn(c -> c.stream() == WasteStream.RECYCLABLE)
                .noneMatch(DemoPlan.Container::noCompaction);
    }

    @Test
    void mergedChecksAddUpNightStopsAndStateEachRule() {
        long nightStops = merged.containers().stream().filter(DemoPlan.Container::nightNoise).count();

        assertThat(merged.compliance()).extracting(Compliance.Check::id).containsExactly("R02", "R03", "R06", "R07", "R10");
        assertThat(check("R02").count()).isEqualTo((int) nightStops);
        assertThat(check("R03").status()).isEqualTo(Status.OK);
        assertThat(check("R06").status()).isEqualTo(Status.OK);
        assertThat(check("R06").summary()).contains("compactadores para no aprovechables");
        assertThat(check("R10").status()).isEqualTo(Status.OK);
    }

    private static Compliance.Check check(String id) {
        return merged.compliance().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
}
