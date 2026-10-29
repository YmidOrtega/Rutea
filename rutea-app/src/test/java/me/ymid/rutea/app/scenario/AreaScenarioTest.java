package me.ymid.rutea.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

        AreaScenario scenario = AreaScenario.build(town, ServiceAreas.ASE_5, 30, 24, 12, 160, 4);
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
        Instance instance = AreaScenario.build(SampleTown.graph(dir), ServiceAreas.ASE_5, 30, 24, 12, 160, 4).instance();
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
}
