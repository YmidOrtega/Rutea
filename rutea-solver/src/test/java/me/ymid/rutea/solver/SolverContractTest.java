package me.ymid.rutea.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import me.ymid.rutea.solver.cvrplib.CvrplibReader;

/**
 * Contrato de todo solver registrado en {@link Solvers}: solución factible en cada instancia de
 * {@code data/cvrplib} y en instancias asimétricas generadas, y la misma solución con la misma
 * semilla. Las asimétricas están porque todas las de CVRPLIB son simétricas y una red vial no lo es.
 */
class SolverContractTest {

    private static final SolverSettings SETTINGS = new SolverSettings(42, Duration.ofSeconds(5));

    static Stream<Arguments> solversAndInstances() throws IOException {
        List<Path> instances;
        try (Stream<Path> files = Files.list(CvrplibData.dir())) {
            instances = files.filter(p -> p.toString().endsWith(".vrp")).sorted().toList();
        }
        return Solvers.all().stream()
                .flatMap(solver -> instances.stream().map(vrp -> Arguments.of(solver, vrp)));
    }

    @ParameterizedTest(name = "{0} · {1}")
    @MethodSource("solversAndInstances")
    void returnsFeasibleAndReproducibleSolution(Solver solver, Path vrp) throws IOException {
        Instance instance = CvrplibReader.readInstance(vrp);

        Solution first = solver.solve(instance, SETTINGS);
        Solution second = solver.solve(instance, SETTINGS);

        assertThat(Evaluator.evaluate(instance, first).violations()).isEmpty();
        assertThat(second).isEqualTo(first);
    }

    static Stream<Arguments> solversAndAsymmetricInstances() {
        List<Instance> instances = List.of(asymmetric(30, 1), asymmetric(60, 2), asymmetric(100, 3));
        return Solvers.all().stream()
                .flatMap(solver -> instances.stream().map(instance -> Arguments.of(solver, instance)));
    }

    @ParameterizedTest(name = "{0} · {1}")
    @MethodSource("solversAndAsymmetricInstances")
    void returnsFeasibleAndReproducibleSolutionWithAsymmetricDistances(Solver solver, Instance instance) {
        Solution first = solver.solve(instance, SETTINGS);
        Solution second = solver.solve(instance, SETTINGS);

        assertThat(Evaluator.evaluate(instance, first).violations()).isEmpty();
        assertThat(second).isEqualTo(first);
    }

    /** Distancia euclidiana más un desvío distinto en cada sentido, como un trazado con contravías. */
    private static Instance asymmetric(int customers, long seed) {
        Random random = new Random(seed);
        int size = customers + 1;
        double[] x = random.doubles(size, 0, 1000).toArray();
        double[] y = random.doubles(size, 0, 1000).toArray();
        DistanceMatrix euclidean = DistanceMatrix.euclidean(x, y);
        int[][] distances = new int[size][size];
        int[] demands = new int[size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                distances[i][j] = i == j ? 0 : euclidean.get(i, j) + random.nextInt(200);
            }
            demands[i] = i == 0 ? 0 : 1 + random.nextInt(20);
        }
        return new Instance("asym-n" + customers, 100, demands, DistanceMatrix.of(distances));
    }
}
