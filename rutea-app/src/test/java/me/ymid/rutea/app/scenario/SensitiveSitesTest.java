package me.ymid.rutea.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.scenario.SensitiveSites.Kind;
import me.ymid.rutea.geo.geometry.Haversine;

class SensitiveSitesTest {

    @Test
    @DisplayName("R07 · centros educativos, hospitales, clínicas y centros asistenciales: sin compactación")
    void mapsOsmTagsToTheKindsTheNormNames() {
        assertThat(SensitiveSites.kindOf(Map.of("amenity", "school"))).contains(Kind.EDUCATION);
        assertThat(SensitiveSites.kindOf(Map.of("amenity", "kindergarten"))).contains(Kind.EDUCATION);
        assertThat(SensitiveSites.kindOf(Map.of("amenity", "hospital"))).contains(Kind.HEALTH);
        assertThat(SensitiveSites.kindOf(Map.of("healthcare", "clinic"))).contains(Kind.HEALTH);
        assertThat(SensitiveSites.kindOf(Map.of("amenity", "nursing_home"))).contains(Kind.CARE);
        assertThat(SensitiveSites.kindOf(Map.of("tourism", "hotel"))).contains(Kind.HOTEL);
        assertThat(SensitiveSites.kindOf(Map.of("amenity", "doctors"))).as("un consultorio no cuenta").isEmpty();
        assertThat(Kind.EDUCATION.noCompaction()).isTrue();
        assertThat(Kind.HOTEL.noCompaction()).isFalse();
    }

    @Test
    @DisplayName("R02 · hoteles, hospitales, clínicas y centros asistenciales: mitigar el ruido de noche")
    void nightNoiseAppliesToTheSitesTheNormNames() {
        assertThat(Kind.HOTEL.nightNoise()).isTrue();
        assertThat(Kind.HEALTH.nightNoise()).isTrue();
        assertThat(Kind.CARE.nightNoise()).isTrue();
        assertThat(Kind.EDUCATION.nightNoise()).isFalse();
    }

    @Test
    void measuresToTheNearestPointOfThePlot() {
        SensitiveSites.Site plot = new SensitiveSites.Site(Kind.EDUCATION, "way/1", "Colegio",
                new int[] {47_000_000, 47_010_000}, new int[] {-740_000_000, -740_000_000});

        assertThat(plot.metersTo(47_010_100, -740_000_000)).isCloseTo(Haversine.meters(47_010_100, -740_000_000,
                47_010_000, -740_000_000), offset(1e-9));
    }

    @Test
    void roundTripsThroughCsvEvenWithCommasInNames(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sitios.csv");
        List<SensitiveSites.Site> sites = List.of(
                new SensitiveSites.Site(Kind.HEALTH, "node/7", "Clínica Norte, sede 2", new int[] {1}, new int[] {2}),
                new SensitiveSites.Site(Kind.EDUCATION, "way/9", "", new int[] {3, 5}, new int[] {4, 6}));

        SensitiveSites.write(file, sites);
        List<SensitiveSites.Site> read = SensitiveSites.read(file);

        assertThat(read).hasSize(2);
        assertThat(read.get(0).name()).isEqualTo("Clínica Norte  sede 2");
        assertThat(read.get(1).latE7()).containsExactly(3, 5);
        assertThat(read.get(1).label()).isEqualTo("centro educativo");
    }
}
