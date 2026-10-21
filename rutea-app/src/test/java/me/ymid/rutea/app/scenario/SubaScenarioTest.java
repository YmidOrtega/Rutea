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

class SubaScenarioTest {

    @TempDir
    Path dir;

    @Test
    void putsTheLandfillGateAtTheDepotAndRoadDistancesBetweenContainers() throws IOException {
        RoadGraph town = SampleTown.graph(dir);

        Instance instance = SubaScenario.build(town, 30, 24, 12, 160, 4).instance();

        assertThat(instance.name()).isEqualTo("Suba-n13-s4");
        assertThat(instance.size()).isEqualTo(13);
        assertThat(instance.demand(0)).isZero();
        assertThat(instance.capacity()).isEqualTo(SubaFigures.COMPACTOR_CAPACITY_KG);
        int gate = town.vertexOf(30).orElseThrow();
        ShortestPaths paths = new ShortestPaths(town, ShortestPaths.Weight.METERS);
        paths.run(gate);
        int firstContainer = SimulatedContainers.place(town, WasteStream.NON_RECYCLABLE, 12, 160, 4)
                .getFirst().vertex();
        assertThat(instance.distance(0, 1)).isEqualTo(paths.distance(firstContainer));
    }

    @Test
    void survivesTheRoundTripThroughCvrplib() throws IOException {
        Instance instance = SubaScenario.build(SampleTown.graph(dir), 30, 24, 12, 160, 4).instance();
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
