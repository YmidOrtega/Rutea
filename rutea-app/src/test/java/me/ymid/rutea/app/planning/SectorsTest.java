package me.ymid.rutea.app.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.domain.WasteStream;
import tools.jackson.databind.json.JsonMapper;

class SectorsTest {

    // Dos UPZ de 0,01° (≈1,1 km) lado a lado; al oriente de la segunda, nada.
    private static final Sectors TWO = Sectors.read(JsonMapper.builder().build().readTree("""
            {"type": "FeatureCollection", "features": [
             {"type": "Feature", "properties": {"ref": "1", "name": "Oeste"}, "geometry": {"type": "Polygon",
              "coordinates": [[[-74.10, 4.70], [-74.09, 4.70], [-74.09, 4.71], [-74.10, 4.71], [-74.10, 4.70]]]}},
             {"type": "Feature", "properties": {"ref": "2", "name": "Este"}, "geometry": {"type": "Polygon",
              "coordinates": [[[-74.09, 4.70], [-74.08, 4.70], [-74.08, 4.71], [-74.09, 4.71], [-74.09, 4.70]]]}}
            ]}"""));

    @Test
    void aPointBelongsToTheUpzThatContainsIt() {
        assertThat(TWO.locate(47_050_000, -740_950_000)).isEqualTo("1");
        assertThat(TWO.locate(47_050_000, -740_850_000)).isEqualTo("2");
    }

    @Test
    void aPointJustOutsideBelongsToTheNearestBorderAndFartherIsRural() {
        // 0,0002° de longitud son ≈22 m a esta latitud; 0,0004°, ≈44 m.
        assertThat(TWO.locate(47_050_000, -740_798_000)).isEqualTo("2");
        assertThat(TWO.locate(47_050_000, -740_796_000)).isEqualTo(Sectors.RURAL);
    }

    @Test
    @DisplayName("R04 · D. 1077/2015 art. 2.3.2.2.2.3.33: cada vehículo publica su pasada por la UPZ en cuartos de hora")
    void passesRoundOutwardToTheQuarterHourAndSplitWhenTheTruckComesBack() {
        List<DemoPlan.Sector> schedule = TWO.schedule(List.of(
                container("N1", "C-01", 1_000, "1"),
                container("N2", "C-01", 2_500, "1"),
                // vuelve 2 h después, en otro viaje
                container("N3", "C-01", 9_700, "1"),
                container("A1", "A-01", 900, "1"),
                container("N4", "C-02", 4_000, Sectors.RURAL)));

        assertThat(schedule).extracting(DemoPlan.Sector::ref).containsExactly("1", Sectors.RURAL);
        assertThat(schedule.getFirst().passes()).containsExactly(
                new DemoPlan.Pass(WasteStream.RECYCLABLE, "A-01", 900, 1_800, 1),
                new DemoPlan.Pass(WasteStream.NON_RECYCLABLE, "C-01", 900, 2_700, 2),
                new DemoPlan.Pass(WasteStream.NON_RECYCLABLE, "C-01", 9_000, 9_900, 1));
        assertThat(schedule.get(1).name()).isEqualTo("Zona rural");
        assertThat(schedule.get(1).outline()).isEmpty();
    }

    @Test
    void theCommittedUpzOfSubaAreTheTwelveOfTheLocality() {
        List<DemoPlan.Sector> sectors = Sectors.of(ServiceAreas.ASE_5).schedule(List.of());

        assertThat(sectors).isEmpty();
        assertThat(Sectors.of(ServiceAreas.ASE_5).locate(47_416_000, -740_833_000)).as("plaza fundacional de Suba").isEqualTo("27");
    }

    private static DemoPlan.Container container(String id, String route, int visitSeconds, String sector) {
        WasteStream stream = id.startsWith("A") ? WasteStream.RECYCLABLE : WasteStream.NON_RECYCLABLE;
        return new DemoPlan.Container(id, stream, new double[] {0, 0}, 100, route, visitSeconds, false, false,
                List.of(), sector);
    }
}
