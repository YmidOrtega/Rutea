package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.nio.file.Path;

import me.ymid.rutea.geo.SampleTown;

/** Escenario sobre el pueblo de prueba: portería en el nodo 30 y base en el nodo 24. */
public final class SubaScenarioFixture {

    private SubaScenarioFixture() {
    }

    public static SubaScenario town(Path dir, int containers, double meanKg) throws IOException {
        return SubaScenario.build(SampleTown.graph(dir), 30, 24, containers, meanKg, 4);
    }
}
