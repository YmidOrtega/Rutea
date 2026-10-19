package me.ymid.rutea.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import me.ymid.rutea.solver.cvrplib.BestKnownSolution;
import me.ymid.rutea.solver.cvrplib.CvrplibReader;

class EvaluatorTest {

    /**
     * Depósito en (0,0); cliente 1 en (3,4) y cliente 2 en (6,8), demanda 5 cada uno, capacidad 8.
     * Distancias: 0-1 = 5, 1-2 = 5, 0-2 = 10.
     */
    private static final Instance LINE = new Instance("line", 8, new int[] {0, 5, 5},
            DistanceMatrix.euclidean(new double[] {0, 3, 6}, new double[] {0, 4, 8}));

    static Stream<String> instances() throws IOException {
        try (Stream<Path> files = Files.list(CvrplibData.dir())) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".vrp"))
                    .map(name -> name.replace(".vrp", ""))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instances")
    void reproducesPublishedCostOfBestKnownSolution(String name) throws IOException {
        Instance instance = CvrplibReader.readInstance(CvrplibData.dir().resolve(name + ".vrp"));
        BestKnownSolution bks = CvrplibReader.readSolution(CvrplibData.dir().resolve(name + ".sol"));

        Evaluation evaluation = Evaluator.evaluate(instance, bks.solution());

        assertThat(evaluation.violations()).isEmpty();
        assertThat(evaluation.cost()).isEqualTo(bks.cost());

        long sumOfRoutes = 0;
        for (int k = 0; k < bks.solution().routeCount(); k++) {
            sumOfRoutes += Evaluator.routeCost(instance, bks.solution(), k);
        }
        assertThat(sumOfRoutes).isEqualTo(bks.cost());
    }

    @Test
    void costIncludesLeavingAndReturningToDepot() {
        Evaluation evaluation = Evaluator.evaluate(LINE, Solution.of(new int[] {1}, new int[] {2}));

        assertThat(evaluation.feasible()).isTrue();
        assertThat(evaluation.cost()).isEqualTo(5 + 5 + 10 + 10);
    }

    @Test
    void reportsRouteOverCapacity() {
        Evaluation evaluation = Evaluator.evaluate(LINE, Solution.of(new int[] {1, 2}));

        assertThat(evaluation.cost()).isEqualTo(5 + 5 + 10);
        assertThat(evaluation.violations()).containsExactly("Ruta #1: carga 10 supera la capacidad 8");
    }

    @Test
    void reportsMissingAndRepeatedCustomers() {
        Evaluation evaluation = Evaluator.evaluate(LINE, Solution.of(new int[] {1}, new int[] {1}));

        assertThat(evaluation.violations())
                .containsExactly("Cliente 1: visitado 2 veces", "Cliente 2: sin visitar");
    }

    @Test
    void reportsDepotOrUnknownNodeInsideRoute() {
        Evaluation evaluation = Evaluator.evaluate(LINE, Solution.of(new int[] {1, 0}, new int[] {2, 3}));

        assertThat(evaluation.violations()).containsExactly(
                "Ruta #1: el nodo 0 no es un cliente (válidos: 1..2)",
                "Ruta #2: el nodo 3 no es un cliente (válidos: 1..2)");
    }

    @Test
    void reportsEmptyRoute() {
        Evaluation evaluation = Evaluator.evaluate(LINE, Solution.of(new int[] {1}, new int[0], new int[] {2}));

        assertThat(evaluation.violations()).containsExactly("Ruta #2: vacía");
    }
}
