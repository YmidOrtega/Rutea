package me.ymid.rutea.app.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.app.scenario.SensitiveSites.Kind;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.app.scenario.SubaScenarioFixture;

class ComplianceTest {

    @TempDir
    static Path dir;

    private static SubaScenario town;
    private static Container first;

    @BeforeAll
    static void town() throws IOException {
        SubaScenario plain = SubaScenarioFixture.town(dir, 10, 160);
        first = plain.containers().getFirst();
        // Un colegio a 10 m del primer contenedor y un hotel justo encima del segundo.
        Container second = plain.containers().get(1);
        town = plain.withSites(List.of(
                new SensitiveSites.Site(Kind.EDUCATION, "node/1", "Colegio", new int[] {first.latE7() + 900},
                        new int[] {first.lonE7()}),
                new SensitiveSites.Site(Kind.HOTEL, "node/2", "Hotel", new int[] {second.latE7()},
                        new int[] {second.lonE7()})));
    }

    @Test
    @DisplayName("R07 · D. 1077/2015 art. 2.3.2.2.2.3.27, num. 5: sin compactación frente a un colegio")
    void marksContainersInFrontOfASchool() {
        Compliance.Result result = Compliance.evaluate(town, new int[10], "18:00");

        assertThat(result.stops().get(0).noCompaction()).isTrue();
        assertThat(result.stops().get(0).reasons().getFirst()).startsWith("R07 · sin compactación: frente a Colegio");
        assertThat(result.stops().get(1).noCompaction()).as("un hotel no impide compactar").isFalse();
        assertThat(check(result, "R07").count()).isEqualTo(1);
        assertThat(check(result, "R07").status()).isEqualTo(Compliance.Status.ACTION);
    }

    @Test
    @DisplayName("R02 · D. 1077/2015 art. 2.3.2.2.2.3.31: de 21:00 a 06:00 se mitiga el ruido")
    void marksOnlyNightStopsInResidentialStreetsOrInFrontOfSensitiveSites() {
        int[] evening = new int[10];
        int[] night = new int[10];
        Arrays.fill(night, 4 * 3600);

        Compliance.Result early = Compliance.evaluate(town, evening, "18:00");
        Compliance.Result late = Compliance.evaluate(town, night, "18:00");

        assertThat(check(early, "R02").count()).as("a las 18:00 no aplica").isZero();
        // En el pueblo de prueba todas las calles locales son residenciales: de noche aplica en todas.
        assertThat(check(late, "R02").count()).isEqualTo(10);
        assertThat(late.stops().get(1).reasons()).anySatisfy(reason -> assertThat(reason).contains("frente a Hotel"));
        assertThat(late.stops().get(0).reasons()).anySatisfy(reason -> assertThat(reason).startsWith("R02 · 22:00"));
    }

    @Test
    @DisplayName("R03 · D. 1077/2015 art. 2.3.2.2.2.3.32: no aprovechables al menos 2 veces por semana")
    void frequencyMeetsTheWeeklyMinimum() {
        Compliance.Check r03 = check(Compliance.evaluate(town, new int[10], "18:00"), "R03");

        assertThat(r03.count()).isZero();
        assertThat(r03.status()).isEqualTo(Compliance.Status.OK);
        assertThat(r03.summary()).isEqualTo("Se recoge 6 veces por semana; el mínimo es 2");
    }

    private static Compliance.Check check(Compliance.Result result, String id) {
        return result.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
}
