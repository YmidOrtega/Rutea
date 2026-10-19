package me.ymid.rutea.solver.benchmark;

import java.time.Duration;
import java.util.Locale;

/**
 * Una fila del CSV: una corrida de un solver sobre una instancia. El costo y la factibilidad
 * vienen del {@code Evaluator}, nunca del solver.
 */
public record BenchmarkResult(
        String instance,
        int customers,
        String solver,
        long seed,
        Duration timeLimit,
        long cost,
        long bestKnownCost,
        boolean feasible,
        Duration wallTime,
        int vehicles) {

    static final String CSV_HEADER =
            "instance,customers,solver,seed,time_limit_ms,cost,bks,gap_pct,feasible,wall_ms,vehicles,machine";

    /** Gap frente a la BKS: {@code (costo − BKS) / BKS}. */
    public double gap() {
        return (double) (cost - bestKnownCost) / bestKnownCost;
    }

    String toCsv(String machine) {
        return String.join(",",
                instance,
                Integer.toString(customers),
                solver,
                Long.toString(seed),
                Long.toString(timeLimit.toMillis()),
                Long.toString(cost),
                Long.toString(bestKnownCost),
                String.format(Locale.ROOT, "%.3f", gap() * 100),
                Boolean.toString(feasible),
                String.format(Locale.ROOT, "%.3f", wallTime.toNanos() / 1e6),
                Integer.toString(vehicles),
                machine);
    }
}
