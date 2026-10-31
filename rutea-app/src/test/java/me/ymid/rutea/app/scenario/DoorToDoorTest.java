package me.ymid.rutea.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.planning.AreaPlanner;
import me.ymid.rutea.app.planning.DemoPlan;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.DoorToDoor.Days;
import me.ymid.rutea.app.scenario.DoorToDoor.Slot;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.SampleTown;
import me.ymid.rutea.geo.ShortestPaths;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

class DoorToDoorTest {

    private static final Locality SUBA = ServiceAreas.locality(11).orElseThrow();

    private static final DoorToDoor.SectorOf QUADRANTS = (lat, lon) -> (lat < 47_000_000 + 50_000 ? "1" : "2")
            + (lon < -740_000_000 + 50_000 ? "1" : "2");

    private static final SideLoading.Shift DAY = SideLoading.ASE_1_DAY;
    private static final SideLoading.Shift NIGHT = SideLoading.ASE_1_NIGHT;

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
    void thereIsADayAndANightShiftOnEachGroupOfDays() {
        assertThat(Slot.ALL).containsExactly(new Slot(Days.MON_WED_FRI, DAY), new Slot(Days.MON_WED_FRI, NIGHT),
                new Slot(Days.TUE_THU_SAT, DAY), new Slot(Days.TUE_THU_SAT, NIGHT));
        assertThat(Slot.ALL).extracting(Slot::code).containsExactly("LMVDia", "LMVNoche", "MJSDia", "MJSNoche");
    }

    @Test
    void sectorsGoLongestFirstToTheLighterSlot() {
        Map<String, Slot> calendar = DoorToDoor.calendar(
                Map.of("a", 500L, "b", 400L, "c", 300L, "d", 200L, "e", 150L, "f", 100L));

        List<Slot> slots = Slot.ALL;
        assertThat(calendar).containsExactlyInAnyOrderEntriesOf(Map.of("a", slots.get(0), "b", slots.get(1), "c",
                slots.get(2), "d", slots.get(3), "e", slots.get(3), "f", slots.get(2)));
    }

    @Test
    void equalSectorsAreSplitByCodeAndAlwaysTheSameWay() {
        assertThat(DoorToDoor.calendar(Map.of("2", 100L, "1", 100L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("1", Slot.ALL.get(0), "2", Slot.ALL.get(1)));
    }

    @Test
    void everyStreetChunkIsCollectedInTheSlotOfItsSector() {
        DoorToDoor.Plan plan = plan(Map.of(), DoorToDoor.COLLECTIONS_PER_WEEK);

        int chunks = 0;
        for (DoorToDoor.Turn turn : plan.turns()) {
            for (StreetChunks.Chunk chunk : turn.chunks()) {
                String sector = QUADRANTS.at(graph.latE7(chunk.entry()), graph.lonE7(chunk.entry()));
                assertThat(plan.calendar().get(sector)).isEqualTo(turn.slot());
            }
            chunks += turn.chunks().size();
        }
        assertThat(chunks).isEqualTo(StreetChunks.of(graph, shape -> true, DoorToDoor.MAX_CHUNK_METERS).size());
        // Una franja sin calles ni contenedores no sale: en el pueblo, un cuadrante no tiene entradas de trozo.
        List<Slot> slots = plan.turns().stream().map(DoorToDoor.Turn::slot).toList();
        assertThat(slots).hasSizeGreaterThan(1).doesNotHaveDuplicates();
        assertThat(Slot.ALL).containsSubsequence(slots);
    }

    @Test
    @DisplayName("Los contenedores van en su turno: los de seis pasadas por semana, los dos grupos de días; los de"
            + " tres, en el de su UPZ (R03, R04)")
    void containersFollowTheirShiftAndFrequency() {
        Container day = container("dia", 20, 300);
        Container night = container("noche", 22, 300);
        Map<SideLoading.Shift, List<Container>> containers = Map.of(DAY, List.of(day), NIGHT, List.of(night));

        DoorToDoor.Plan daily = plan(containers, 2 * DoorToDoor.COLLECTIONS_PER_WEEK);
        assertThat(daily.turns()).allSatisfy(turn -> assertThat(turn.containers())
                .containsExactly(turn.slot().shift().equals(DAY) ? day : night));

        DoorToDoor.Plan thrice = plan(containers, DoorToDoor.COLLECTIONS_PER_WEEK);
        for (DoorToDoor.Turn turn : thrice.turns()) {
            for (Container container : turn.containers()) {
                assertThat(thrice.calendar().get(QUADRANTS.at(container.latE7(), container.lonE7())).days())
                        .isEqualTo(turn.slot().days());
                assertThat(turn.slot().shift()).isEqualTo(container == day ? DAY : NIGHT);
            }
        }
        assertThat(thrice.turns()).flatExtracting(DoorToDoor.Turn::containers).containsExactlyInAnyOrder(day, night);
    }

    @Test
    void containersOfAShiftThatIsNeitherDayNorNightAreRejected() {
        SideLoading.Shift evening = new SideLoading.Shift("Tarde", "", "14:00", 8 * 3600);

        assertThatThrownBy(() -> plan(Map.of(evening, List.of(container("tarde", 20, 300))),
                DoorToDoor.COLLECTIONS_PER_WEEK)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStreetsCarryWhatTheContainersLeaveForTheDaysBetweenTwoPasses() {
        Map<SideLoading.Shift, List<Container>> containers = Map.of(DAY,
                List.of(container("oeste", 20, 300), container("este", 22, 300)));
        int perWeek = 2 * DoorToDoor.COLLECTIONS_PER_WEEK;

        DoorToDoor.Plan plan = plan(containers, perWeek);

        assertThat(plan.containerKgPerDay()).isCloseTo(600.0 * perWeek / 7, within(1e-9));
        double expected = (KG_PER_DAY - plan.containerKgPerDay()) * 7 / DoorToDoor.COLLECTIONS_PER_WEEK;
        double streets = 0;
        for (DoorToDoor.Turn turn : plan.turns()) {
            Instance instance = turn.instance();
            for (int node = turn.containers().size() + 1; node < instance.size(); node++) {
                streets += instance.demand(node);
            }
        }
        int chunks = plan.turns().stream().mapToInt(turn -> turn.chunks().size()).sum();
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
    @DisplayName("PENDIENTE_VERIFICAR: un trozo es una parada que se recorre recogiendo a 6 km/h")
    void aChunkIsAStopThatCollectsItsStreetAtWalkingPace() {
        StreetChunks.Chunk first = chunks().getFirst();
        AreaScenario scenario = scenario(List.of(container("oeste", 20, 300)));

        assertThat(scenario.gate()).isEqualTo(chunks().size() + 2);
        assertThat(scenario.isChunk(1)).isFalse();
        assertThat(scenario.isChunk(2)).isTrue();
        assertThat(scenario.isChunk(scenario.gate())).isFalse();
        assertThat(scenario.serviceSeconds(1)).isEqualTo(BogotaFigures.SERVICE_SECONDS_PER_CONTAINER);
        assertThat(scenario.collectingMeters(1)).isZero();
        assertThat(scenario.serviceSeconds(2)).isEqualTo((int) Math.round(first.meters() * 0.6));
        assertThat(scenario.collectingMeters(2)).isEqualTo(first.meters());
        assertThat(scenario.travel().target(2)).isEqualTo(first.entry());
        assertThat(scenario.travel().source(2)).isEqualTo(first.exit());
        assertThat(scenario.points()[2]).isEqualTo(first.entry());
    }

    @Test
    void theScenarioPlansTheSameInstanceThatIsExported() {
        List<Container> containers = List.of(container("oeste", 20, 300));
        AreaScenario scenario = scenario(containers);

        Instance exported = DoorToDoor.instance(graph, containers, chunks(), depot, gate,
                scenario.instance().capacity(), 1, "prueba");
        assertSameDistances(scenario.instance(), exported);
        assertSameDistances(scenario.withTravel(scenario.travel()).instance(), exported);
    }

    @Test
    void aTruckDrivesAndCollectsTheWholeChunk() {
        AreaScenario scenario = scenario(List.of());
        int[][] routes = new int[scenario.instance().size() - 1][];
        for (int node = 1; node < scenario.instance().size(); node++) {
            routes[node - 1] = new int[] {node};
        }
        Solution solution = Solution.of(routes);

        ShiftScheduler.Schedule schedule = ShiftScheduler.schedule(scenario, solution, 0, 24 * 3600, 1);

        Instance instance = scenario.instance();
        for (ShiftScheduler.Truck truck : schedule.trucks()) {
            int node = solution.customer(truck.trips()[0], 0);
            assertThat(truck.meters()).isEqualTo((long) instance.distance(0, node) + instance.distance(node, 0)
                    + scenario.returnMeters());
        }
    }

    @Test
    void aDrawnTruckDrivesEveryChunkItCollects() {
        AreaScenario scenario = scenario(List.of(container("oeste", 20, 300)));
        Solution solution = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(1, Duration.ofMinutes(1), 200));

        // Dibujar falla si un camión no mide lo mismo, en metros y segundos, que su asignación.
        DemoPlan plan = AreaPlanner.plan(scenario, solution, "alns", 1, 200);

        assertThat(plan.routes().stream().flatMap(route -> route.stops().stream()).toList())
                .hasSize(scenario.instance().size() - 1).doesNotHaveDuplicates().contains("oeste", "T-1");
        assertThat(plan.containers()).extracting(DemoPlan.Container::id).containsExactly("oeste");
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
        assertThatThrownBy(() -> DoorToDoor.plan(graph, sumapaz, AreaScenarioFixture.SUBA_TOWN, QUADRANTS, Map.of(),
                DoorToDoor.COLLECTIONS_PER_WEEK, depot, gate, KG_PER_DAY, "")).isInstanceOf(
                        IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(Map.of(), 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void containersThatCollectMoreThanTheLocalityProducesAreAnError() {
        List<Container> heavy = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            heavy.add(container("c" + i, 20, 300));
        }
        assertThatThrownBy(() -> plan(Map.of(DAY, heavy), 2 * DoorToDoor.COLLECTIONS_PER_WEEK))
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

    private static DoorToDoor.Plan plan(Map<SideLoading.Shift, List<Container>> containers, int perWeek) {
        return DoorToDoor.plan(graph, SUBA, AreaScenarioFixture.SUBA_TOWN, QUADRANTS, containers, perWeek, depot, gate,
                KG_PER_DAY, "");
    }

    private static List<StreetChunks.Chunk> chunks() {
        return StreetChunks.of(graph, shape -> true, DoorToDoor.MAX_CHUNK_METERS);
    }

    private static AreaScenario scenario(List<Container> containers) {
        return AreaScenario.buildDoorToDoor(graph, ServiceAreas.ASE_5, ServiceAreas.ASE_5.verified(), containers,
                chunks(), depot, gate, 1, "prueba");
    }

    private static void assertSameDistances(Instance actual, Instance expected) {
        assertThat(actual.size()).isEqualTo(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.demand(i)).isEqualTo(expected.demand(i));
            for (int j = 0; j < expected.size(); j++) {
                assertThat(actual.distance(i, j)).as("%d → %d", i, j).isEqualTo(expected.distance(i, j));
            }
        }
    }

    private static Container container(String id, long osmNode, int kg) {
        int vertex = graph.vertexOf(osmNode).orElseThrow();
        return new Container(id, WasteStream.NON_RECYCLABLE, graph.latE7(vertex), graph.lonE7(vertex), vertex, 0, kg);
    }
}
