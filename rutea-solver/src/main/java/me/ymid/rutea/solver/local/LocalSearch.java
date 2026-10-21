package me.ymid.rutea.solver.local;

import java.util.Random;

import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;

public final class LocalSearch {

    private final Instance instance;
    private final int[][] neighbors;

    public LocalSearch(Instance instance) {
        this.instance = instance;
        this.neighbors = LocalSearchSolver.neighbors(instance);
    }

    public Solution improve(Solution start, Random random, long deadline) {
        Routes routes = new Routes(instance, start);
        new LocalSearchSolver.Search(instance, routes, neighbors).run(random, deadline);
        return routes.toSolution();
    }
}
