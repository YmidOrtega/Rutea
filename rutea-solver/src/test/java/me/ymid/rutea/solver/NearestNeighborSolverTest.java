package me.ymid.rutea.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class NearestNeighborSolverTest {

    private static final SolverSettings SETTINGS = new SolverSettings(1, Duration.ofSeconds(1));

    @Test
    void visitsNearestCustomerThatFitsAndOpensNewRouteWhenNoneFits() {
        // Depósito en 0; clientes sobre una recta en x = 1, 2, 10, 11; capacidad para dos
        Instance instance = new Instance("line", 2, new int[] {0, 1, 1, 1, 1},
                DistanceMatrix.euclidean(new double[] {0, 1, 2, 10, 11}, new double[5]));

        Solution solution = new NearestNeighborSolver().solve(instance, SETTINGS);

        assertThat(solution).isEqualTo(Solution.of(new int[] {1, 2}, new int[] {3, 4}));
    }

    @Test
    void skipsNearerCustomerThatNoLongerFits() {
        // Tras visitar 1 (carga 2 de 3), el 2 está más cerca pero no cabe; el 3 sí
        Instance instance = new Instance("skip", 3, new int[] {0, 2, 2, 1},
                DistanceMatrix.euclidean(new double[] {0, 1, 2, 5}, new double[4]));

        Solution solution = new NearestNeighborSolver().solve(instance, SETTINGS);

        assertThat(solution).isEqualTo(Solution.of(new int[] {1, 3}, new int[] {2}));
    }
}
