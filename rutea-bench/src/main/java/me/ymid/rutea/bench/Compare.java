package me.ymid.rutea.bench;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import me.ymid.rutea.bench.timefold.TimefoldSolver;
import me.ymid.rutea.solver.Solver;
import me.ymid.rutea.solver.Solvers;
import me.ymid.rutea.solver.benchmark.BenchmarkHarness;

/** El arnés de rutea-solver con Timefold disponible: mvn -pl rutea-bench -am compile exec:java@compare. */
public final class Compare {

    private Compare() {
    }

    public static void main(String[] args) throws IOException {
        List<Solver> solvers = new ArrayList<>(Solvers.all());
        solvers.add(new TimefoldSolver());
        BenchmarkHarness.main(args, solvers);
    }
}
