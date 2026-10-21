package me.ymid.rutea.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DieselEmissionsTest {

    @Test
    @DisplayName("IPCC 2006, vol. 2: 74.100 kg CO2/TJ (Tabla 3.2.1) y 43,0 TJ/Gg (Tabla 1.2)")
    void usesTheIpccDefaults() {
        assertThat(DieselEmissions.KG_CO2_PER_TJ).isEqualTo(74_100);
        assertThat(DieselEmissions.TJ_PER_GG).isEqualTo(43.0);
    }

    @Test
    void oneLiterGivesAboutTwoPointSixEightKilos() {
        assertThat(DieselEmissions.kgCo2PerLiter()).isCloseTo(2.6765, offset(1e-4));
        assertThat(DieselEmissions.kgCo2(100)).isCloseTo(267.65, offset(0.01));
    }

    @Test
    void refusesNegativeFuel() {
        assertThatThrownBy(() -> DieselEmissions.kgCo2(-1)).hasMessageContaining("negativos");
    }
}
