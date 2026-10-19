package me.ymid.rutea.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import me.ymid.rutea.solver.cvrplib.CvrplibReader;

/**
 * Contrato de todo solver registrado en {@link Solvers}: solución factible en cada instancia de
 * {@code data/cvrplib} y la misma solución con la misma semilla.
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
}
