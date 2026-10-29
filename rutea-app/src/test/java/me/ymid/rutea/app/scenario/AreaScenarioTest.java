package me.ymid.rutea.app.scenario;

import static me.ymid.rutea.app.scenario.AreaScenarioFixture.EAST;
import static me.ymid.rutea.app.scenario.AreaScenarioFixture.FARM;
import static me.ymid.rutea.app.scenario.AreaScenarioFixture.HALVES_TOWN;
import static me.ymid.rutea.app.scenario.AreaScenarioFixture.TWO_HALVES;
import static me.ymid.rutea.app.scenario.AreaScenarioFixture.WEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.SampleTown;
import me.ymid.rutea.geo.ShortestPaths;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.cvrplib.CvrplibReader;
import me.ymid.rutea.solver.cvrplib.CvrplibWriter;

class AreaScenarioTest {

    @TempDir
    Path dir;

    @Test
    void leavesFromTheBaseAndArrivesAtTheLandfillGate() throws IOException {
        RoadGraph town = SampleTown.graph(dir);

        AreaScenario scenario = AreaScenarioFixture.town(dir, 12, 160);
        Instance instance = scenario.instance();

        assertThat(instance.name()).isEqualTo("SubaBase-n13-s4");
        assertThat(instance.size()).isEqualTo(13);
        assertThat(instance.demand(0)).isZero();
        assertThat(instance.capacity()).isEqualTo(8_000);
        int gate = town.vertexOf(30).orElseThrow();
        int base = town.vertexOf(24).orElseThrow();
        int firstContainer = SimulatedContainers.place(town, WasteStream.NON_RECYCLABLE, 12, 160, 4)
                .getFirst().vertex();
        ShortestPaths paths = new ShortestPaths(town, ShortestPaths.Weight.METERS);

        assertThat(instance.distance(0, 1)).as("base → contenedor").isEqualTo(paths.distances(base, new int[] {firstContainer})[0]);
        assertThat(instance.distance(1, 0)).as("contenedor → portería").isEqualTo(paths.distances(firstContainer, new int[] {gate})[0]);
        assertThat(scenario.returnMeters()).as("portería → base").isEqualTo(paths.distances(gate, new int[] {base})[0]);
        assertThat(scenario.points()[0]).isEqualTo(base);
    }

    @Test
    void survivesTheRoundTripThroughCvrplib() throws IOException {
        Instance instance = AreaScenarioFixture.town(dir, 12, 160).instance();
        Path file = dir.resolve(instance.name() + ".vrp");

        CvrplibWriter.writeExplicit(file, instance, "prueba");
        Instance read = CvrplibReader.readInstance(file);

        for (int i = 0; i < instance.size(); i++) {
            assertThat(read.demand(i)).isEqualTo(instance.demand(i));
            for (int j = 0; j < instance.size(); j++) {
                assertThat(read.distance(i, j)).isEqualTo(instance.distance(i, j));
            }
        }
    }

    @Test
    void aContainerStaysPutWhateverTheSelection() throws IOException {
        RoadGraph town = SampleTown.graph(dir);
        List<Container> whole = halves(town, List.of(WEST, EAST, FARM)).containers();
        List<Container> west = halves(town, List.of(WEST)).containers();
        List<Container> east = halves(town, List.of(EAST)).containers();

        assertThat(west).isNotEmpty();
        assertThat(east).isNotEmpty();
        assertThat(whole).containsAll(west).containsAll(east).hasSize(west.size() + east.size());
        assertThat(halves(town, List.of(EAST, WEST)).containers()).containsExactlyElementsOf(whole);
    }

    @Test
    void choosingEveryLocalityReproducesTheWholeArea() throws IOException {
        RoadGraph town = SampleTown.graph(dir);
        Instance whole = halves(town, TWO_HALVES.localities()).instance();
        Instance reversed = halves(town, TWO_HALVES.localities().reversed()).instance();

        assertThat(reversed.name()).isEqualTo(whole.name()).isEqualTo("ASE9Base-n21-s4");
        for (int i = 0; i < whole.size(); i++) {
            assertThat(reversed.demand(i)).isEqualTo(whole.demand(i));
            for (int j = 0; j < whole.size(); j++) {
                assertThat(reversed.distance(i, j)).isEqualTo(whole.distance(i, j));
            }
        }
    }

    @Test
    void namesAPartialSelectionAfterItsLocalities() throws IOException {
        AreaScenario west = halves(SampleTown.graph(dir), List.of(WEST));

        assertThat(west.instance().name()).isEqualTo("OesteBase-n" + (west.containers().size() + 1) + "-s4");
    }

    @Test
    void rejectsAnEmptyForeignOrRuralOnlySelection() throws IOException {
        RoadGraph town = SampleTown.graph(dir);

        assertThatThrownBy(() -> halves(town, List.of())).hasMessageContaining("no hay localidades elegidas");
        assertThatThrownBy(() -> halves(town, List.of(ServiceAreas.locality(11).orElseThrow())))
                .hasMessageContaining("Suba no es de ASE 9");
        assertThatThrownBy(() -> halves(town, List.of(FARM))).hasMessageContaining("suelo rural");
    }

    private static AreaScenario halves(RoadGraph town, List<Locality> selection) {
        return AreaScenario.build(town, TWO_HALVES, new ArrayList<>(selection), HALVES_TOWN, 30, 24, 20, 160, 4);
    }
}
