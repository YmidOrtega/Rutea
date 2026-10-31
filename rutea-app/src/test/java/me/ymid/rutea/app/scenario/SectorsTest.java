package me.ymid.rutea.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
    void theCommittedUpzOfSubaAreTheTwelveOfTheLocality() {
        assertThat(Sectors.of(ServiceAreas.ASE_5).locate(47_416_000, -740_833_000)).as("plaza fundacional de Suba").isEqualTo("27");
    }

    @Test
    void aUpzInSeveralPartsLocatesInAnyAndDrawsTheLargest() {
        Sectors parts = Sectors.read(JsonMapper.builder().build().readTree("""
                {"type": "FeatureCollection", "features": [
                 {"type": "Feature", "properties": {"ref": "60", "name": "Partida"}, "geometry": {
                  "type": "MultiPolygon", "coordinates": [
                   [[[-74.10, 4.70], [-74.099, 4.70], [-74.099, 4.701], [-74.10, 4.701], [-74.10, 4.70]]],
                   [[[-74.09, 4.70], [-74.08, 4.70], [-74.08, 4.71], [-74.09, 4.71], [-74.09, 4.70]]]]}}
                ]}"""));

        assertThat(parts.locate(47_005_000, -740_995_000)).isEqualTo("60");
        assertThat(parts.locate(47_050_000, -740_850_000)).isEqualTo("60");
        assertThat(parts.locate(47_050_000, -740_950_000)).isEqualTo(Sectors.RURAL);
        assertThat(parts.upz().getFirst().outline()).hasSize(5).first()
                .satisfies(point -> assertThat(point).containsExactly(-74.09, 4.70));
    }

    @Test
    void everyAreaReadsTheUpzOfItsUrbanLocalities() {
        assertThat(Sectors.of(ServiceAreas.ASE_1).locate(45_981_000, -740_760_000)).as("plaza de Bolívar")
                .isEqualTo("94");
        assertThat(Sectors.of(ServiceAreas.ASE_2).locate(45_981_000, -740_760_000)).isEqualTo(Sectors.RURAL);
        for (ServiceAreas.ServiceArea area : ServiceAreas.BOGOTA) {
            assertThat(Sectors.of(area)).as(area.name()).isNotNull();
        }
    }
}
