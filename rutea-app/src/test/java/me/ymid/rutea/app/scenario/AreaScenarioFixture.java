package me.ymid.rutea.app.scenario;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.ServiceAreas.ServiceArea;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.SampleTown;
import me.ymid.rutea.geo.geometry.Polygon;

/** Escenario del ASE 5 sobre el pueblo de prueba: portería en el nodo 30 y base en el nodo 24. */
public final class AreaScenarioFixture {

    /** Todo el pueblo es Suba. */
    static final Localities SUBA_TOWN = new Localities(
            Map.of(ServiceAreas.locality(11).orElseThrow(), rectangle(-1, -1, 11, 11)));

    /**
     * Una ASE de prueba con la operación de Área Limpia: el pueblo partido en dos por la longitud del paso 6, y una
     * localidad rural fuera de él.
     */
    static final Locality WEST = new Locality(1, "Oeste", 91, false);
    static final Locality EAST = new Locality(2, "Este", 92, false);
    static final Locality FARM = new Locality(20, "Campo", 93, true);
    static final ServiceArea TWO_HALVES = new ServiceArea(9, "Prueba", List.of(WEST, EAST, FARM), 1,
            ServiceAreas.ASE_5.operation());
    static final Localities HALVES_TOWN = new Localities(Map.of(WEST, rectangle(-1, -1, 11, 6), EAST,
            rectangle(-1, 6, 11, 11), FARM, rectangle(-30, 30, -20, 50)));

    private AreaScenarioFixture() {
    }

    public static AreaScenario town(Path dir, int containers, double meanKg) throws IOException {
        return AreaScenario.build(SampleTown.graph(dir), ServiceAreas.ASE_5, ServiceAreas.ASE_5.localities(), SUBA_TOWN,
                30, 24, containers, meanKg, 4);
    }

    /** Aprovechables: la "ECA" del pueblo es el nodo 24, que hace de salida y de llegada. */
    public static AreaScenario recyclableTown(Path dir, int containers, double meanKg) throws IOException {
        RoadGraph graph = SampleTown.graph(dir);
        int eca = graph.vertexOf(24).orElseThrow();
        return AreaScenario.build(graph, ServiceAreas.ASE_5, ServiceAreas.ASE_5.localities(), SUBA_TOWN,
                WasteStream.RECYCLABLE, eca, eca, BogotaFigures.RECYCLABLE_VEHICLE_KG, containers, meanKg, 5,
                "Reciclaje");
    }

    /** Rectángulo en pasos de 0,001° desde (4,70; -74,00), como {@link SampleTown}. */
    static Polygon rectangle(int latFrom, int lonFrom, int latTo, int lonTo) {
        int[] lat = {step(latFrom, 47_000_000), step(latFrom, 47_000_000), step(latTo, 47_000_000),
                step(latTo, 47_000_000)};
        int[] lon = {step(lonFrom, -740_000_000), step(lonTo, -740_000_000), step(lonTo, -740_000_000),
                step(lonFrom, -740_000_000)};
        return new Polygon(List.of(lat), List.of(lon));
    }

    private static int step(int steps, int origin) {
        return origin + 10_000 * steps;
    }
}
