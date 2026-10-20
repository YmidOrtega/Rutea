package me.ymid.rutea.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ClarkeWrightSolverTest {

    private static final SolverSettings SETTINGS = new SolverSettings(1, Duration.ofSeconds(1));

    @Test
    void mergesPairsWithLargestSavingsWhileTheyFit() {
        // Depósito en 0; clientes sobre una recta en x = 1, 2, 10, 11; capacidad para dos
        Instance instance = new Instance("line", 2, new int[] {0, 1, 1, 1, 1},
                DistanceMatrix.euclidean(new double[] {0, 1, 2, 10, 11}, new double[5]));

        Solution solution = new ClarkeWrightSolver().solve(instance, SETTINGS);

        assertThat(solution).isEqualTo(Solution.of(new int[] {1, 2}, new int[] {3, 4}));
    }

    @Test
    void neverReversesARouteWhenDistancesAreAsymmetric() {
        // 1 → 2 ahorra 19 y se une primero. 3 → 2 ahorraría 18, pero 2 ya no es inicio de ruta;
        // invertir 1 → 2 para dejarlo al inicio cambiaría el costo, porque 2 → 1 cuesta 100.
        int[][] distances = {
            {0, 10, 10, 10},
            {10, 0, 1, 50},
            {10, 100, 0, 50},
            {10, 50, 2, 0},
        };
        Instance instance = new Instance("one-way", 100, new int[] {0, 1, 1, 1}, DistanceMatrix.of(distances));

        Solution solution = new ClarkeWrightSolver().solve(instance, SETTINGS);

        assertThat(solution).isEqualTo(Solution.of(new int[] {1, 2}, new int[] {3}));
        assertThat(Evaluator.evaluate(instance, solution).cost()).isEqualTo(21 + 20);
    }

    @Test
    void reversesARouteWhenDistancesAreSymmetric() {
        // Primero se unen 1 → 2 y 3 → 4. El siguiente ahorro es 1–3, pero los dos son inicio de
        // ruta: con matriz simétrica se invierte 1 → 2 sin cambiar su costo y queda 2 → 1 → 3 → 4
        int[][] distances = {
            {0, 10, 10, 10, 10},
            {10, 0, 1, 3, 30},
            {10, 1, 0, 30, 30},
            {10, 3, 30, 0, 1},
            {10, 30, 30, 1, 0},
        };
        Instance instance = new Instance("two-way", 100, new int[] {0, 1, 1, 1, 1}, DistanceMatrix.of(distances));

        Solution solution = new ClarkeWrightSolver().solve(instance, SETTINGS);

        assertThat(solution).isEqualTo(Solution.of(new int[] {2, 1, 3, 4}));
        assertThat(Evaluator.evaluate(instance, solution).cost()).isEqualTo(10 + 1 + 3 + 1 + 10);
    }
}
