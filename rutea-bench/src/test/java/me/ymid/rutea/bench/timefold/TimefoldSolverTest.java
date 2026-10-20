package me.ymid.rutea.bench.timefold;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.cvrplib.CvrplibReader;

class TimefoldSolverTest {

    @Test
    void returnsAFeasibleSolutionEvaluatedByOurEvaluator() throws IOException {
        Instance instance = CvrplibReader.readInstance(
                Path.of(System.getProperty("rutea.cvrplib.dir")).resolve("X-n101-k25.vrp"));

        var solution = new TimefoldSolver().solve(instance, new SolverSettings(1, Duration.ofSeconds(3)));

        assertThat(Evaluator.evaluate(instance, solution).violations()).isEmpty();
    }
}
