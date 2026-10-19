package me.ymid.rutea.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class CollectionRulesTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "20:59, false",
            "21:00, true",   // extremo incluido
            "23:59, true",
            "00:00, true",   // cruza la medianoche
            "05:59, true",
            "06:00, true",   // extremo incluido
            "06:01, false",
            "12:00, false",
    })
    @DisplayName("R02 · D. 1077/2015 art. 2.3.2.2.2.3.31: franja nocturna de 21:00 a 06:00")
    void nightWindowCrossesMidnight(LocalTime time, boolean night) {
        assertThat(CollectionRules.NIGHT_WINDOW.contains(time)).isEqualTo(night);
    }

    @Test
    @DisplayName("R03 · D. 1077/2015 art. 2.3.2.2.2.3.32: no aprovechables al menos 2 veces por semana")
    void nonRecyclablesAtLeastTwiceAWeek() {
        assertThat(CollectionRules.minimumWeeklyFrequency(WasteStream.NON_RECYCLABLE)).hasValue(2);
    }

    @Test
    @DisplayName("R03 · la norma no fija frecuencia mínima para aprovechables ni orgánicos")
    void noMinimumForOtherStreams() {
        assertThat(CollectionRules.minimumWeeklyFrequency(WasteStream.RECYCLABLE)).isEmpty();
        assertThat(CollectionRules.minimumWeeklyFrequency(WasteStream.ORGANIC)).isEmpty();
    }

    @Test
    @DisplayName("R02 y R03 · citan el parágrafo verificado de su artículo")
    void citesVerifiedArticles() {
        assertThat(CollectionRules.NIGHT_NOISE.citation())
                .isEqualTo("R02 · Decreto 1077 de 2015, art. 2.3.2.2.2.3.31, parágrafo");
        assertThat(CollectionRules.MINIMUM_FREQUENCY.citation())
                .isEqualTo("R03 · Decreto 1077 de 2015, art. 2.3.2.2.2.3.32, parágrafo");
    }

    @Test
    @DisplayName("Una regla sin verificar lo dice en su cita")
    void pendingRuleSaysSoInCitation() {
        NormRef pending = new NormRef("R99", "Norma X", "art. 1",
                URI.create("https://example.org"), Verification.PENDIENTE_VERIFICAR);

        assertThat(pending.citation()).isEqualTo("R99 · Norma X, art. 1 (PENDIENTE_VERIFICAR)");
    }
}
