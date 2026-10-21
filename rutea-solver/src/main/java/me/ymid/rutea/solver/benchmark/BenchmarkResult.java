package me.ymid.rutea.solver.benchmark;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * El costo y la factibilidad vienen del Evaluator, nunca del solver. Una instancia sin BKS publicada,
 * como las de Suba, deja vacíos la BKS y el gap: nunca se inventan.
 *
 * @param warm si antes de esta corrida se resolvió la misma instancia una vez, para calentar la JVM
 */
public record BenchmarkResult(
        String instance,
        int customers,
        String solver,
        long seed,
        Duration timeLimit,
        long iterationLimit,
        long cost,
        OptionalLong bestKnownCost,
        boolean feasible,
        Duration wallTime,
        int vehicles,
        boolean warm) {

    static final String CSV_HEADER = "instance,customers,solver,seed,time_limit_ms,iteration_limit,cost,bks,gap_pct,"
            + "feasible,wall_ms,vehicles,warm,machine";

    public BenchmarkResult {
        Objects.requireNonNull(bestKnownCost, "bestKnownCost");
    }

    public OptionalDouble gap() {
        if (bestKnownCost.isEmpty()) {
            return OptionalDouble.empty();
        }
        long bks = bestKnownCost.getAsLong();
        return OptionalDouble.of((double) (cost - bks) / bks);
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
                bestKnownCost.isPresent() ? Long.toString(bestKnownCost.getAsLong()) : "",
                gap().isPresent() ? String.format(Locale.ROOT, "%.3f", gap().getAsDouble() * 100) : "",
                Boolean.toString(feasible),
                String.format(Locale.ROOT, "%.3f", wallTime.toNanos() / 1e6),
                Integer.toString(vehicles),
                Boolean.toString(warm),
                machine);
    }
}
