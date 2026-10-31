package me.ymid.rutea.app.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.domain.DieselEmissions;
import me.ymid.rutea.domain.WasteStream;

class PlanReportTest {

    private static DemoPlan plan(String method, long... metersPerTrip) {
        List<DemoPlan.Route> routes = new ArrayList<>();
        for (int i = 0; i < metersPerTrip.length; i++) {
            routes.add(new DemoPlan.Route("C-" + i, WasteStream.NON_RECYCLABLE, 8_000, 7_500, metersPerTrip[i], 3_600,
                    new int[] {7_500}, List.of(), new int[0], List.of(), new int[0]));
        }
        DemoPlan.Meta meta = new DemoPlan.Meta("Suba", true, "", method, 1, 0, "18:00", 28_800, 60, 2_400, 0.8, 2.6765, "", List.of());
        return new DemoPlan(meta, List.of(), List.of(), routes, List.of(), List.of(), List.of());
    }

    @Test
    void addsUpKmHoursTonnesAndCo2() {
        PlanReport report = PlanReport.of(plan("x", 50_000, 70_000));

        assertThat(report.trips()).isEqualTo(2);
        assertThat(report.total().km()).isEqualTo(120.0);
        assertThat(report.total().hours()).isEqualTo(2.0);
        assertThat(report.total().tonnes()).isEqualTo(15.0);
        assertThat(report.total().liters()).isCloseTo(120 * BogotaFigures.LITERS_PER_KM, offset(1e-9));
        assertThat(report.total().kgCo2()).isCloseTo(DieselEmissions.kgCo2(120 * BogotaFigures.LITERS_PER_KM), offset(1e-9));
    }

    @Test
    void co2SavingsMatchKmSavingsWhateverTheAssumedConsumption() {
        PlanReport baseline = PlanReport.of(plan("barrido", 100_000, 100_000));
        PlanReport rutea = PlanReport.of(plan("alns", 80_000, 70_000));

        assertThat(rutea.savingsVs(baseline, PlanReport.Line::km)).isCloseTo(0.25, offset(1e-12));
        assertThat(rutea.savingsVs(baseline, PlanReport.Line::kgCo2)).isCloseTo(0.25, offset(1e-12));
    }
}
