package me.ymid.rutea.solver;

/**
 * Debe devolver una solución factible, y la misma con la misma semilla: SolverContractTest lo
 * comprueba para todo solver de {@link Solvers}.
 */
public interface Solver {

    String name();

    Solution solve(Instance instance, SolverSettings settings);
}
