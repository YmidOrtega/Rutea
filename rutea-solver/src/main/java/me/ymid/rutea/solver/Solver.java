package me.ymid.rutea.solver;

/**
 * Un algoritmo para el CVRP. Debe devolver una solución factible para cualquier {@link Instance}
 * válida y ser reproducible con la misma semilla; {@code SolverContractTest} lo comprueba
 * para todo solver registrado en {@link Solvers}.
 */
public interface Solver {

    /** Nombre corto y estable; identifica al solver en el arnés y en los CSV. */
    String name();

    Solution solve(Instance instance, SolverSettings settings);
}
