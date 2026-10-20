package me.ymid.rutea.solver.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import me.ymid.rutea.solver.ClarkeWrightSolver;
import me.ymid.rutea.solver.CvrplibData;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.cvrplib.CvrplibReader;

class LocalSearchSolverTest {

    private static final SolverSettings SETTINGS = new SolverSettings(1, Duration.ofSeconds(5));

    @Test
    void assertionsAreOnSoEveryAppliedMoveIsCheckedAgainstTheRealCost() {
        // Cada movimiento aplicado afirma que su delta coincide con el costo recalculado; sin -ea
        // esas comprobaciones no corren y un delta mal calculado pasaría inadvertido
        assertThat(LocalSearchSolver.class.desiredAssertionStatus()).isTrue();
    }

    static Stream<Path> instances() throws IOException {
        return Files.list(CvrplibData.dir()).filter(p -> p.toString().endsWith(".vrp")).sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instances")
    void neverEndsWorseThanClarkeWright(Path vrp) throws IOException {
        Instance instance = CvrplibReader.readInstance(vrp);

        long savings = Evaluator.evaluate(instance, new ClarkeWrightSolver().solve(instance, SETTINGS)).cost();
        long local = Evaluator.evaluate(instance, new LocalSearchSolver().solve(instance, SETTINGS)).cost();

        assertThat(local).isLessThanOrEqualTo(savings);
    }

    @Test
    void untanglesACrossedRoute() {
        // Cuadrado de lado 10 con el depósito en una esquina: 0 → 1 → 2 → 3 → 0 cruza las diagonales
        Instance instance = new Instance("square", 10, new int[] {0, 1, 1, 1},
                DistanceMatrix.euclidean(new double[] {0, 0, 10, 10}, new double[] {0, 10, 0, 10}));
        Routes routes = new Routes(instance, Solution.of(new int[] {1, 2, 3}));
        assertThat(routes.cost()).isEqualTo(48);

        new LocalSearchSolver.Search(instance, routes, LocalSearchSolver.neighbors(instance))
                .run(new Random(1), Long.MAX_VALUE);

        assertThat(routes.cost()).isEqualTo(40);
        assertThat(routes.toSolution()).isIn(Solution.of(new int[] {1, 3, 2}), Solution.of(new int[] {2, 3, 1}));
    }

    @Test
    void neighborsAreClosestInEitherDirection() {
        // De 1 a 3 cuesta 50, pero de 3 a 1 solo 1: 3 es el vecino más cercano de 1
        int[][] distances = {
            {0, 10, 10, 10},
            {10, 0, 5, 50},
            {10, 5, 0, 20},
            {10, 1, 20, 0},
        };
        Instance instance = new Instance("one-way", 10, new int[] {0, 1, 1, 1}, DistanceMatrix.of(distances));

        assertThat(LocalSearchSolver.neighbors(instance)[1]).containsExactly(3, 2);
    }
}
