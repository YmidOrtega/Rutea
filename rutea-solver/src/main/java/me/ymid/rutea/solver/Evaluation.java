package me.ymid.rutea.solver;

import java.util.List;

/**
 * Resultado de evaluar una solución: su costo y las restricciones que incumple.
 *
 * @param cost       suma de las distancias recorridas, incluida la vuelta al depósito
 * @param violations una descripción por restricción incumplida; vacía si la solución es factible
 */
public record Evaluation(long cost, List<String> violations) {

    public Evaluation {
        violations = List.copyOf(violations);
    }

    public boolean feasible() {
        return violations.isEmpty();
    }
}
