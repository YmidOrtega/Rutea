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
        assertThat(lines.subList(1, lines.size())).allSatisfy(line -> assertThat(line.split(",")).hasSize(13));
        assertThat(results).allSatisfy(result -> {
            assertThat(result.feasible()).isTrue();
            assertThat(result.cost()).isGreaterThanOrEqualTo(result.bestKnownCost());
        });
    }

    @Test
    void gapIsRelativeToBestKnownCost() {
        BenchmarkResult result = new BenchmarkResult("i", 10, "s", 1, Duration.ofSeconds(1), 500,
                110, 100, true, Duration.ofMillis(3), 2);

        assertThat(result.gap()).isEqualTo(0.10, within(1e-12));
        assertThat(result.toCsv("m")).isEqualTo("i,10,s,1,1000,500,110,100,10.000,true,3.000,2,m");
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
