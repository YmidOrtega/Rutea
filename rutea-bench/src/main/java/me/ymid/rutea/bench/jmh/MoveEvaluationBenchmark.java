package me.ymid.rutea.bench.jmh;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import me.ymid.rutea.solver.Evaluation;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.cvrplib.CvrplibReader;
import me.ymid.rutea.solver.local.LocalSearch;
import me.ymid.rutea.solver.local.LocalSearchSolver;

/**
 * Parte de un óptimo local: la pasada de búsqueda local evalúa todos los movimientos granulares y no
 * aplica ninguno, así que mide solo el costo de evaluarlos.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MoveEvaluationBenchmark {

    @Param({"X-n101-k25", "X-n200-k36", "X-n298-k31"})
    public String instance;

    private Instance problem;
    private Solution localOptimum;
    private LocalSearch localSearch;

    @Setup
    public void setUp() throws IOException {
        Path dir = Path.of(System.getProperty("rutea.cvrplib.dir", "data/cvrplib/X"));
        problem = CvrplibReader.readInstance(dir.resolve(instance + ".vrp"));
        localOptimum = new LocalSearchSolver().solve(problem, new SolverSettings(1, Duration.ofMinutes(1)));
        localSearch = new LocalSearch(problem);
    }

    @Benchmark
    public Solution localSearchPass() {
        return localSearch.improve(localOptimum, new Random(1), Long.MAX_VALUE);
    }

    @Benchmark
    public Evaluation evaluate() {
        return Evaluator.evaluate(problem, localOptimum);
    }
}
