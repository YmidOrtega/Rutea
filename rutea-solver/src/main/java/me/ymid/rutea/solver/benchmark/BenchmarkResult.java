package me.ymid.rutea.solver.benchmark;

import java.time.Duration;
import java.util.Locale;

/** El costo y la factibilidad vienen del Evaluator, nunca del solver. */
public record BenchmarkResult(
        String instance,
        int customers,
        String solver,
        long seed,
        Duration timeLimit,
        long iterationLimit,
        long cost,
        long bestKnownCost,
        boolean feasible,
        Duration wallTime,
        int vehicles) {

    static final String CSV_HEADER =
            "instance,customers,solver,seed,time_limit_ms,iteration_limit,cost,bks,gap_pct,feasible,wall_ms,vehicles,machine";

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
                iterationLimit == Long.MAX_VALUE ? "" : Long.toString(iterationLimit),
                Long.toString(cost),
                Long.toString(bestKnownCost),
                String.format(Locale.ROOT, "%.3f", gap() * 100),
                Boolean.toString(feasible),
                String.format(Locale.ROOT, "%.3f", wallTime.toNanos() / 1e6),
                Integer.toString(vehicles),
                machine);
    }
}
