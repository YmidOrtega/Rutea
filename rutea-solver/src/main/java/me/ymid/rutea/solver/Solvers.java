package me.ymid.rutea.solver;

import java.util.List;
import java.util.Optional;

import me.ymid.rutea.solver.alns.AlnsSolver;
import me.ymid.rutea.solver.local.LocalSearchSolver;

/** Agregar un solver aquí basta para que el arnés lo mida y SolverContractTest lo pruebe. */
public final class Solvers {

    private static final List<Solver> ALL = List.of(new NearestNeighborSolver(), new ClarkeWrightSolver(), new LocalSearchSolver(), new AlnsSolver());

    private Solvers() {
    }

    public static List<Solver> all() {
        return ALL;
    }

    public static Optional<Solver> byName(String name) {
        return ALL.stream().filter(solver -> solver.name().equals(name)).findFirst();
    }
}
