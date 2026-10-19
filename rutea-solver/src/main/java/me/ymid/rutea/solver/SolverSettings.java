package me.ymid.rutea.solver;

import java.time.Duration;
import java.util.Objects;

/**
 * Parámetros de una corrida. Con la misma instancia y los mismos parámetros, un solver devuelve
 * siempre la misma solución.
 *
 * @param seed      semilla de toda la aleatoriedad de la corrida
 * @param timeLimit tiempo máximo de reloj; los solvers constructivos pueden terminar antes
 */
public record SolverSettings(long seed, Duration timeLimit) {

    public SolverSettings {
        Objects.requireNonNull(timeLimit, "timeLimit");
        if (timeLimit.isNegative() || timeLimit.isZero()) {
            throw new IllegalArgumentException("el límite de tiempo debe ser positivo, es " + timeLimit);
        }
    }
}
