package me.ymid.rutea.solver.alns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import me.ymid.rutea.solver.CvrplibData;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.NearestNeighborSolver;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.cvrplib.CvrplibReader;

class AlnsWarmStartTest {

    private static final SolverSettings SETTINGS = new SolverSettings(5, Duration.ofSeconds(60), 150);

    private static Instance instance;
    private static Solution start;

    @BeforeAll
    static void read() throws IOException {
        instance = CvrplibReader.readInstance(CvrplibData.dir().resolve("X-n101-k25.vrp"));
        // Un arranque malo a propósito: el vecino más cercano deja mucho que mejorar.
        start = new NearestNeighborSolver().solve(instance, SETTINGS);
    }

    @Test
    void neverReturnsWorseThanTheStartingPlan() {
        Solution warm = new AlnsSolver().solveFrom(instance, SETTINGS, start);

        assertThat(Evaluator.evaluate(instance, warm).violations()).isEmpty();
        assertThat(Evaluator.evaluate(instance, warm).cost()).isLessThan(Evaluator.evaluate(instance, start).cost());
    }

    @Test
    void sameSeedAndStartGiveTheSameSolution() {
        long once = Evaluator.evaluate(instance, new AlnsSolver().solveFrom(instance, SETTINGS, start)).cost();
        long again = Evaluator.evaluate(instance, new AlnsSolver().solveFrom(instance, SETTINGS, start)).cost();

        assertThat(again).isEqualTo(once);
    }

    @Test
    void refusesAnInfeasibleStart() {
        int[] everyone = new int[instance.customerCount()];
        for (int i = 0; i < everyone.length; i++) {
            everyone[i] = i + 1;
        }
        Solution overloaded = Solution.of(everyone);

        assertThatThrownBy(() -> new AlnsSolver().solveFrom(instance, SETTINGS, overloaded))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no es factible");
    }
}
