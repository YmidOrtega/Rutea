package me.ymid.rutea.solver.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.solver.CvrplibData;
import me.ymid.rutea.solver.NearestNeighborSolver;
import me.ymid.rutea.solver.SolverSettings;

class BenchmarkHarnessTest {

    @TempDir
    Path out;

    @Test
    void writesOneCsvRowPerInstanceSortedBySize() throws IOException {
        List<BenchmarkResult> results = BenchmarkHarness.run(CvrplibData.dir(),
                List.of(new NearestNeighborSolver()), new SolverSettings(7, Duration.ofSeconds(1)));
        Path csv = out.resolve("run.csv");

        BenchmarkHarness.writeCsv(csv, results, "test-machine");

        long instances;
        try (Stream<Path> files = Files.list(CvrplibData.dir())) {
            instances = files.filter(file -> file.toString().endsWith(".vrp")).count();
        }
        List<String> lines = Files.readAllLines(csv);
        assertThat(lines).hasSize((int) instances + 1);
        assertThat(lines.get(0)).isEqualTo(BenchmarkResult.CSV_HEADER);
        assertThat(lines.get(1)).startsWith("X-n101-k25,100,nearest-neighbor,7,1000,,");
        assertThat(lines.subList(1, lines.size())).extracting(line -> Integer.parseInt(line.split(",")[1]))
                .isSorted();
        assertThat(lines.subList(1, lines.size())).allSatisfy(line -> assertThat(line.split(",")).hasSize(14));
        assertThat(results).allSatisfy(result -> {
            assertThat(result.feasible()).isTrue();
            assertThat(result.cost()).isGreaterThanOrEqualTo(result.bestKnownCost().orElseThrow());
        });
    }

    @Test
    void gapIsRelativeToBestKnownCost() {
        BenchmarkResult result = new BenchmarkResult("i", 10, "s", 1, Duration.ofSeconds(1), 500,
                110, OptionalLong.of(100), true, Duration.ofMillis(3), 2, false);

        assertThat(result.gap().getAsDouble()).isEqualTo(0.10, within(1e-12));
        assertThat(result.toCsv("m")).isEqualTo("i,10,s,1,1000,500,110,100,10.000,true,3.000,2,false,m");
    }

    @Test
    void leavesBksAndGapEmptyWhenThereIsNoPublishedBks() {
        BenchmarkResult result = new BenchmarkResult("suba", 806, "s", 2, Duration.ofSeconds(60), Long.MAX_VALUE,
                1_231_800, OptionalLong.empty(), true, Duration.ofSeconds(60), 17, true);

        assertThat(result.gap()).isEmpty();
        assertThat(result.toCsv("m")).isEqualTo("suba,806,s,2,60000,,1231800,,,true,60000.000,17,true,m");
    }

    @Test
    void runsEverySeedAndAcceptsInstancesWithoutBks(@TempDir Path instances) throws IOException {
        Files.writeString(instances.resolve("asym.vrp"), """
                NAME : asym
                TYPE : CVRP
                DIMENSION : 3
                EDGE_WEIGHT_TYPE : EXPLICIT
                EDGE_WEIGHT_FORMAT : FULL_MATRIX
                CAPACITY : 10
                EDGE_WEIGHT_SECTION
                0 4 6
                3 0 5
                9 7 0
                DEMAND_SECTION
                1 0
                2 4
                3 6
                DEPOT_SECTION
                1
                -1
                EOF
                """);

        List<BenchmarkResult> results = BenchmarkHarness.run(instances, List.of(new NearestNeighborSolver()),
                List.of(1L, 2L, 3L), Duration.ofSeconds(1), Long.MAX_VALUE, true);

        assertThat(results).extracting(BenchmarkResult::seed).containsExactly(1L, 2L, 3L);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.bestKnownCost()).isEmpty();
            assertThat(result.warm()).isTrue();
            assertThat(result.feasible()).isTrue();
        });
    }

    @Test
    void machineDescriptionHasNoCommas() {
        assertThat(BenchmarkHarness.machine()).doesNotContain(",").contains("Java");
    }

    @Test
    void neverOverwritesAPreviousRun() throws IOException {
        Path csv = out.resolve("run.csv");
        BenchmarkHarness.writeCsv(csv, List.of(), "test-machine");

        assertThatExceptionOfType(FileAlreadyExistsException.class)
                .isThrownBy(() -> BenchmarkHarness.writeCsv(csv, List.of(), "test-machine"));
    }
}
