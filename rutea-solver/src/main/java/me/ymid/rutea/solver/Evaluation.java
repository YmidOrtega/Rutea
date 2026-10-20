package me.ymid.rutea.solver;

import java.util.List;

public record Evaluation(long cost, List<String> violations) {

    public Evaluation {
        violations = List.copyOf(violations);
    }

    public boolean feasible() {
        return violations.isEmpty();
    }
}
