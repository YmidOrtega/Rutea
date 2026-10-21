package me.ymid.rutea.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.SampleTown;
import me.ymid.rutea.geo.geometry.Haversine;

class SimulatedContainersTest {

    @TempDir
    static Path dir;

    private static RoadGraph town;

    @BeforeAll
    static void buildTown() throws IOException {
        town = SampleTown.graph(dir);
    }

    @Test
    void placesContainersOnlyOnLocalStreetsInsideTheLocality() {
        List<Container> containers = SimulatedContainers.place(town, WasteStream.NON_RECYCLABLE, 20, 160, 1);

        assertThat(containers).hasSize(20);
        for (Container container : containers) {
            assertThat(town.shapeInLocality(container.shape())).as(container.id()).isTrue();
            assertThat(isWithinShapeBounds(container)).as(container.id()).isTrue();
        }
    }

    @Test
    void keepsTheMinimumSpacing() {
        List<Container> containers = SimulatedContainers.place(town, WasteStream.NON_RECYCLABLE, 30, 160, 5);

        for (int i = 0; i < containers.size(); i++) {
            for (int j = i + 1; j < containers.size(); j++) {
                Container a = containers.get(i);
                Container b = containers.get(j);
                assertThat(Haversine.meters(a.latE7(), a.lonE7(), b.latE7(), b.lonE7()))
                        .as("%s y %s", a.id(), b.id())
                        .isGreaterThanOrEqualTo(SimulatedContainers.MIN_SPACING_METERS);
            }
        }
    }

    @Test
    void visitsEachContainerAtAnEndOfItsStreet() {
        for (Container container : SimulatedContainers.place(town, WasteStream.NON_RECYCLABLE, 20, 160, 2)) {
            int first = town.firstShapePoint(container.shape());
            int last = town.endShapePoint(container.shape()) - 1;
            int vertex = container.vertex();
            boolean atStart = town.latE7(vertex) == town.shapeLatE7(first) && town.lonE7(vertex) == town.shapeLonE7(first);
            boolean atEnd = town.latE7(vertex) == town.shapeLatE7(last) && town.lonE7(vertex) == town.shapeLonE7(last);

            assertThat(atStart || atEnd).as(container.id()).isTrue();
        }
    }

    @Test
    void theSameSeedGivesTheSameContainers() {
        List<Container> once = SimulatedContainers.place(town, WasteStream.NON_RECYCLABLE, 15, 160, 9);
        List<Container> again = SimulatedContainers.place(town, WasteStream.NON_RECYCLABLE, 15, 160, 9);
        List<Container> other = SimulatedContainers.place(town, WasteStream.NON_RECYCLABLE, 15, 160, 10);

        assertThat(again).isEqualTo(once);
        assertThat(other).isNotEqualTo(once);
        assertThat(once.getFirst().id()).isEqualTo("N0001");
    }

    @Test
    void loadsVaryAroundTheMean() {
        List<Container> containers = SimulatedContainers.place(town, WasteStream.NON_RECYCLABLE, 30, 160, 3);

        double mean = containers.stream().mapToInt(Container::kg).average().orElseThrow();
        assertThat(containers).allSatisfy(c -> assertThat(c.kg()).isBetween(80, 240));
        assertThat(mean).isBetween(140.0, 180.0);
    }

    @Test
    void figuresFollowTheirSources() {
        assertThat(SubaFigures.nonRecyclableContainers()).isEqualTo(806);
        assertThat(SubaFigures.containerKgPerDay()).isEqualTo(110_760);
        assertThat(SubaFigures.meanKgPerCollection(806)).isCloseTo(160.3, offset(0.1));
    }

    @Test
    void refusesMoreContainersThanTheStreetsCanHold() {
        // Unos 2,9 km de calle local a 50 m mínimos: no caben 500.
        assertThatThrownBy(() -> SimulatedContainers.place(town, WasteStream.NON_RECYCLABLE, 500, 160, 1))
                .hasMessageContaining("separados por 50 m");
    }

    private static boolean isWithinShapeBounds(Container container) {
        int minLat = Integer.MAX_VALUE;
        int maxLat = Integer.MIN_VALUE;
        int minLon = Integer.MAX_VALUE;
        int maxLon = Integer.MIN_VALUE;
        for (int p = town.firstShapePoint(container.shape()); p < town.endShapePoint(container.shape()); p++) {
            minLat = Math.min(minLat, town.shapeLatE7(p));
            maxLat = Math.max(maxLat, town.shapeLatE7(p));
            minLon = Math.min(minLon, town.shapeLonE7(p));
            maxLon = Math.max(maxLon, town.shapeLonE7(p));
        }
        return container.latE7() >= minLat && container.latE7() <= maxLat
                && container.lonE7() >= minLon && container.lonE7() <= maxLon;
    }
}
