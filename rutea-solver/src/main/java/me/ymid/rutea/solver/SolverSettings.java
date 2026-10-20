package me.ymid.rutea.solver;

import java.time.Duration;
import java.util.Objects;

/**
 * Parámetros de una corrida. Con la misma instancia y los mismos parámetros, un solver devuelve
 * siempre la misma solución, siempre que el límite de tiempo no corte la búsqueda: el tiempo
 * depende de la máquina, las iteraciones no. Por eso los solvers iterativos aceptan además un
 * límite de iteraciones; si es el que se alcanza primero, el resultado es reproducible en
 * cualquier máquina.
 *
 * @param seed           semilla de toda la aleatoriedad de la corrida
 * @param timeLimit      tiempo máximo de reloj; los solvers constructivos pueden terminar antes
 * @param iterationLimit iteraciones máximas de un solver iterativo; {@link Long#MAX_VALUE} si no hay
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

    /** Sin límite de iteraciones: la corrida se detiene por tiempo. */
    public SolverSettings(long seed, Duration timeLimit) {
        this(seed, timeLimit, Long.MAX_VALUE);
    }

    public boolean hasIterationLimit() {
        return iterationLimit != Long.MAX_VALUE;
    }

    /**
     * Instante de {@link System#nanoTime()} en que vence el límite de tiempo, contado desde ahora.
     * Un límite tan largo que no cabe en un {@code long} de nanosegundos no vence nunca.
     */
    public long deadlineFromNow() {
        long now = System.nanoTime();
        try {
            return Math.addExact(now, timeLimit.toNanos());
        } catch (ArithmeticException tooLong) {
            return Long.MAX_VALUE;
        }
    }
}
