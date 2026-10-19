package me.ymid.rutea.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WasteStreamTest {

    @Test
    @DisplayName("R09 · Res. 2184/2019 art. 4: blanco aprovechables, verde orgánicos, negro no aprovechables")
    void followsOfficialColorCode() {
        assertThat(WasteStream.RECYCLABLE.color()).isEqualTo(WasteStream.BinColor.WHITE);
        assertThat(WasteStream.ORGANIC.color()).isEqualTo(WasteStream.BinColor.GREEN);
        assertThat(WasteStream.NON_RECYCLABLE.color()).isEqualTo(WasteStream.BinColor.BLACK);
        assertThat(WasteStream.values()).hasSize(3);
    }

    @Test
    @DisplayName("R09 · cada corriente usa un color distinto")
    void colorsAreUnique() {
        assertThat(WasteStream.values()).extracting(WasteStream::color).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("R09 · la cita apunta al artículo 4 verificado")
    void citesVerifiedArticle() {
        assertThat(WasteStream.COLOR_CODE.citation()).isEqualTo("R09 · Resolución 2184 de 2019, art. 4");
    }
}
