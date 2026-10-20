package me.ymid.rutea.solver.cvrplib;

import java.util.Objects;

import me.ymid.rutea.solver.Solution;

public record BestKnownSolution(Solution solution, long cost) {

    public BestKnownSolution {
        Objects.requireNonNull(solution, "solution");
    }
}
