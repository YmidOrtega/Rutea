package me.ymid.rutea.app.planning;

import java.util.List;
import java.util.function.ToDoubleFunction;

import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.domain.DieselEmissions;

/**
 * Cifras de un plan, por turno y en total: km (con el regreso a la base), horas (manejo y paradas, sin la
 * descarga), toneladas, litros de diésel y kg de CO₂. Los litros usan el consumo supuesto de
 * {@link BogotaFigures#LITERS_PER_KM}; el CO₂, los valores del IPCC de {@link DieselEmissions}.
 */
public record PlanReport(String method, List<Line> lines, Line total) {

    public record Line(String id, double km, double hours, double tonnes, double liters, double kgCo2) {

        static Line of(String id, long meters, long seconds, long kg) {
            double km = meters / 1000.0;
            double liters = km * BogotaFigures.LITERS_PER_KM;
            return new Line(id, km, seconds / 3600.0, kg / 1000.0, liters, DieselEmissions.kgCo2(liters));
        }
    }

    public PlanReport {
        lines = List.copyOf(lines);
    }

    public static PlanReport of(DemoPlan plan) {
        List<Line> lines = plan.routes().stream()
                .map(r -> Line.of(r.id(), r.distanceMeters(), r.durationSeconds(), r.loadKg())).toList();
        long meters = plan.routes().stream().mapToLong(DemoPlan.Route::distanceMeters).sum();
        long seconds = plan.routes().stream().mapToLong(DemoPlan.Route::durationSeconds).sum();
        long kg = plan.routes().stream().mapToLong(DemoPlan.Route::loadKg).sum();
        return new PlanReport(plan.meta().solver(), lines, Line.of("total", meters, seconds, kg));
    }

    public int trips() {
        return lines.size();
    }

    public double savingsVs(PlanReport baseline, ToDoubleFunction<Line> metric) {
        double base = metric.applyAsDouble(baseline.total);
        return (base - metric.applyAsDouble(total)) / base;
    }
}
