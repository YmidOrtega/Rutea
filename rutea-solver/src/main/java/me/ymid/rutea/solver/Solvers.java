package me.ymid.rutea.solver;

import java.util.List;
import java.util.Optional;

/**
 * Registro de los solvers disponibles. El arnés de benchmarks y {@code SolverContractTest} leen
 * de aquí, así que un solver nuevo queda medido y probado con solo agregarlo a la lista.
 */
public final class Solvers {

    private static final List<Solver> ALL = List.of(new NearestNeighborSolver(), new ClarkeWrightSolver());

    private Solvers() {
    }

    public static List<Solver> all() {
        return ALL;
    }

    public static Optional<Solver> byName(String name) {
        return ALL.stream().filter(solver -> solver.name().equals(name)).findFirst();
    }
}
