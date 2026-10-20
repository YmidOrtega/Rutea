package me.ymid.rutea.solver.local;

import java.util.Random;

import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;

/**
 * La búsqueda local de {@link LocalSearchSolver}, para mejorar soluciones que vienen de otro lado
 * (por ejemplo, las que reconstruye ALNS). Calcula las listas de vecinos una sola vez por instancia.
 */
public final class LocalSearch {

    private final Instance instance;
    private final int[][] neighbors;

    public LocalSearch(Instance instance) {
        this.instance = instance;
        this.neighbors = LocalSearchSolver.neighbors(instance);
    }

    /**
     * Lleva {@code start} a un óptimo local, o hasta {@code deadline} ({@link System#nanoTime()}).
     * Nunca devuelve una solución más cara que {@code start}.
     */
    public Solution improve(Solution start, Random random, long deadline) {
        Routes routes = new Routes(instance, start);
        new LocalSearchSolver.Search(instance, routes, neighbors).run(random, deadline);
        return routes.toSolution();
    }
}
