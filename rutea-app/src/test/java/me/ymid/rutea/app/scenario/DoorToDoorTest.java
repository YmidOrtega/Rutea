package me.ymid.rutea.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.scenario.DoorToDoor.Days;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.SampleTown;
import me.ymid.rutea.geo.ShortestPaths;
import me.ymid.rutea.solver.Instance;

class DoorToDoorTest {

    private static final Locality SUBA = ServiceAreas.locality(11).orElseThrow();

    /** Oeste y este del pueblo, partidos por la longitud del paso 5. */
    private static final DoorToDoor.SectorOf HALVES = (lat, lon) -> lon < -740_000_000 + 50_000 ? "1" : "2";

    private static final double KG_PER_DAY = 1_500;

    private static RoadGraph graph;
    private static int depot;
    private static int gate;

    @BeforeAll
    static void town(@TempDir Path dir) throws IOException {
        graph = SampleTown.graph(dir);
        depot = graph.vertexOf(24).orElseThrow();
        gate = graph.vertexOf(30).orElseThrow();
    }

    @Test
    void sectorsGoLongestFirstToTheLighterGroup() {
        Map<String, Days> calendar = DoorToDoor.calendar(Map.of("a", 500L, "b", 400L, "c", 300L, "d", 200L));

        assertThat(calendar).containsExactlyInAnyOrderEntriesOf(Map.of("a", Days.MON_WED_FRI, "b", Days.TUE_THU_SAT,
                "c", Days.TUE_THU_SAT, "d", Days.MON_WED_FRI));
    }

    @Test
    void equalSectorsAreSplitByCodeAndAlwaysTheSameWay() {
        assertThat(DoorToDoor.calendar(Map.of("2", 100L, "1", 100L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("1", Days.MON_WED_FRI, "2", Days.TUE_THU_SAT));
    }

    @Test
    void everyStreetChunkIsCollectedOnTheDaysOfItsSector() {
        DoorToDoor.Plan plan = plan(List.of(), DoorToDoor.COLLECTIONS_PER_WEEK);

        int chunks = 0;
        for (DoorToDoor.Day day : plan.days()) {
            for (StreetChunks.Chunk chunk : day.chunks()) {
                String sector = HALVES.at(graph.latE7(chunk.entry()), graph.lonE7(chunk.entry()));
                assertThat(plan.calendar().get(sector)).isEqualTo(day.days());
            }
            chunks += day.chunks().size();
        }
        assertThat(chunks).isEqualTo(StreetChunks.of(graph, shape -> true, DoorToDoor.MAX_CHUNK_METERS).size());
        assertThat(plan.days()).extracting(DoorToDoor.Day::days).containsExactly(Days.values());
    }

    @Test
    @DisplayName("Los contenedores de seis pasadas por semana van todos los días; los de tres, en los de su UPZ (R03,"
            + " R04)")
    void containersFollowTheirFrequency() {
        List<Container> containers = List.of(container("oeste", 20, 300), container("este", 22, 300));

        DoorToDoor.Plan daily = plan(containers, 2 * DoorToDoor.COLLECTIONS_PER_WEEK);
        assertThat(daily.days()).allSatisfy(day -> assertThat(day.containers()).isEqualTo(containers));

        DoorToDoor.Plan thrice = plan(containers, DoorToDoor.COLLECTIONS_PER_WEEK);
        for (DoorToDoor.Day day : thrice.days()) {
            for (Container container : day.containers()) {
                assertThat(thrice.calendar().get(HALVES.at(container.latE7(), container.lonE7())))
                        .isEqualTo(day.days());
            }
        }
        assertThat(thrice.days()).flatExtracting(DoorToDoor.Day::containers)
                .containsExactlyInAnyOrderElementsOf(containers);
    }

    @Test
    void theStreetsCarryWhatTheContainersLeaveForTheDaysBetweenTwoPasses() {
        List<Container> containers = List.of(container("oeste", 20, 300), container("este", 22, 300));
        int perWeek = 2 * DoorToDoor.COLLECTIONS_PER_WEEK;

        DoorToDoor.Plan plan = plan(containers, perWeek);

        assertThat(plan.containerKgPerDay()).isCloseTo(600.0 * perWeek / 7, within(1e-9));
        double expected = (KG_PER_DAY - plan.containerKgPerDay()) * 7 / DoorToDoor.COLLECTIONS_PER_WEEK;
        double streets = 0;
        for (DoorToDoor.Day day : plan.days()) {
            Instance instance = day.instance();
            for (int node = day.containers().size() + 1; node < instance.size(); node++) {
                streets += instance.demand(node);
            }
        }
        int chunks = plan.days().stream().mapToInt(day -> day.chunks().size()).sum();
        assertThat(streets).isCloseTo(expected, within(0.5 * chunks));
    }

    @Test
    void enteringAChunkCostsTheWayToItPlusTheStreetItCollects() {
        List<StreetChunks.Chunk> chunks = StreetChunks.of(graph, shape -> true, DoorToDoor.MAX_CHUNK_METERS);
        Container container = container("oeste", 20, 300);

        Instance instance = DoorToDoor.instance(graph, List.of(container), chunks, depot, gate, 8_000, 1, "prueba");

        ShortestPaths paths = new ShortestPaths(graph, ShortestPaths.Weight.METERS);
        StreetChunks.Chunk first = chunks.getFirst();
        assertThat(instance.distance(0, 1)).isEqualTo(paths.distances(depot, new int[] {container.vertex()})[0]);
        assertThat(instance.distance(1, 0)).isEqualTo(paths.distances(container.vertex(), new int[] {gate})[0]);
        assertThat(instance.distance(1, 2))
                .isEqualTo(paths.distances(container.vertex(), new int[] {first.entry()})[0] + first.meters());
        assertThat(instance.distance(2, 1)).isEqualTo(paths.distances(first.exit(), new int[] {container.vertex()})[0]);
        assertThat(instance.demand(1)).isEqualTo(300);
        assertThat(instance.demand(2)).isEqualTo(first.meters());
    }

    @Test
    void aChunkThatDoesNotFitInATruckIsRejected() {
        List<StreetChunks.Chunk> chunks = StreetChunks.of(graph, shape -> true, DoorToDoor.MAX_CHUNK_METERS);

        assertThatThrownBy(() -> DoorToDoor.instance(graph, List.of(), chunks, depot, gate, 10, 1, "prueba"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no cabe");
    }

    @Test
    void rejectsARuralLocalityAndAFrequencyOutsideTheDayGroups() {
        Locality sumapaz = ServiceAreas.locality(20).orElseThrow();
        assertThatThrownBy(() -> DoorToDoor.plan(graph, sumapaz, AreaScenarioFixture.SUBA_TOWN, HALVES, List.of(),
                DoorToDoor.COLLECTIONS_PER_WEEK, depot, gate, KG_PER_DAY, "")).isInstanceOf(
                        IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(List.of(), 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void containersThatCollectMoreThanTheLocalityProducesAreAnError() {
        List<Container> heavy = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            heavy.add(container("c" + i, 20, 300));
        }
        assertThatThrownBy(() -> plan(heavy, 2 * DoorToDoor.COLLECTIONS_PER_WEEK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void figuresAddUpToTheCity() {
        long population = 0;
        double kg = 0;
        for (int number = 1; number <= 20; number++) {
            Locality locality = ServiceAreas.locality(number).orElseThrow();
            population += BogotaFigures.urbanPopulation(locality);
            kg += BogotaFigures.kgPerDay(locality);
        }
        assertThat(population).isEqualTo(BogotaFigures.BOGOTA_URBAN_POPULATION);
        assertThat(kg).isCloseTo(BogotaFigures.BOGOTA_ORDINARY_TONNES_PER_DAY * 1000.0, within(1e-6));
        assertThat(BogotaFigures.kgPerDay(SUBA) / 1000).isCloseTo(940.6, within(0.05));
    }

    private static DoorToDoor.Plan plan(List<Container> containers, int perWeek) {
        return DoorToDoor.plan(graph, SUBA, AreaScenarioFixture.SUBA_TOWN, HALVES, containers, perWeek, depot, gate,
                KG_PER_DAY, "");
    }

    private static Container container(String id, long osmNode, int kg) {
        int vertex = graph.vertexOf(osmNode).orElseThrow();
        return new Container(id, WasteStream.NON_RECYCLABLE, graph.latE7(vertex), graph.lonE7(vertex), vertex, 0, kg);
    }
}
