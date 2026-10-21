package me.ymid.rutea.solver;

import java.time.Duration;
import java.util.Objects;

/**
 * El tiempo depende de la máquina y las iteraciones no: si el límite de iteraciones se alcanza
 * primero, la corrida es reproducible en cualquier máquina.
 */
public record SolverSettings(long seed, Duration timeLimit, long iterationLimit) {

    public SolverSettings {
        Objects.requireNonNull(timeLimit, "timeLimit");
        if (timeLimit.isNegative() || timeLimit.isZero()) {
            throw new IllegalArgumentException("el límite de tiempo debe ser positivo, es " + timeLimit);
        }
        if (iterationLimit <= 0) {
            throw new IllegalArgumentException("el límite de iteraciones debe ser positivo, es " + iterationLimit);
        }
    }

    public SolverSettings(long seed, Duration timeLimit) {
        this(seed, timeLimit, Long.MAX_VALUE);
    }

    public boolean hasIterationLimit() {
        return iterationLimit != Long.MAX_VALUE;
    }

    public long deadlineFromNow() {
        long now = System.nanoTime();
        try {
            return Math.addExact(now, timeLimit.toNanos());
        } catch (ArithmeticException tooLong) {
            return Long.MAX_VALUE;
        }
    }
}
