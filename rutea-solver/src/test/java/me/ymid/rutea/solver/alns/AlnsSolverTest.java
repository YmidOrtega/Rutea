package me.ymid.rutea.solver.alns;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import me.ymid.rutea.solver.CvrplibData;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.cvrplib.CvrplibReader;
import me.ymid.rutea.solver.local.LocalSearchSolver;

class AlnsSolverTest {

    private static final SolverSettings SETTINGS = new SolverSettings(3, Duration.ofSeconds(60), 200);

    @ParameterizedTest
    @ValueSource(strings = {"X-n101-k25", "X-n148-k46", "X-n200-k36"})
    void improvesOnLocalSearchAndParallelSearchesNeverEndWorse(String name) throws IOException {
        Instance instance = CvrplibReader.readInstance(CvrplibData.dir().resolve(name + ".vrp"));

        long local = Evaluator.evaluate(instance, new LocalSearchSolver().solve(instance, SETTINGS)).cost();
        long single = Evaluator.evaluate(instance, new AlnsSolver().solve(instance, SETTINGS)).cost();
        long parallel = Evaluator.evaluate(instance, new AlnsSolver(4).solve(instance, SETTINGS)).cost();

        assertThat(single).isLessThan(local);
        assertThat(parallel).isLessThanOrEqualTo(single);
    }
}
