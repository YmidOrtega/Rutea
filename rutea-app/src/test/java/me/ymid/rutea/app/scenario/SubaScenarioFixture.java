package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.nio.file.Path;

import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.SampleTown;

/** Escenario sobre el pueblo de prueba: portería en el nodo 30 y base en el nodo 24. */
public final class SubaScenarioFixture {

    private SubaScenarioFixture() {
    }

    public static SubaScenario town(Path dir, int containers, double meanKg) throws IOException {
        return SubaScenario.build(SampleTown.graph(dir), 30, 24, containers, meanKg, 4);
    }

    /** Aprovechables: la "ECA" del pueblo es el nodo 24, que hace de salida y de llegada. */
    public static SubaScenario recyclableTown(Path dir, int containers, double meanKg) throws IOException {
        RoadGraph graph = SampleTown.graph(dir);
        int eca = graph.vertexOf(24).orElseThrow();
        return SubaScenario.build(graph, WasteStream.RECYCLABLE, eca, eca, SubaFigures.RECYCLABLE_VEHICLE_KG,
                containers, meanKg, 5, "PuebloReciclaje");
    }
}
