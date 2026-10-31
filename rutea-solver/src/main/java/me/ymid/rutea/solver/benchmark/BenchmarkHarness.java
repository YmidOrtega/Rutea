package me.ymid.rutea.solver.benchmark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LongSummaryStatistics;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import me.ymid.rutea.solver.Evaluation;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.Solver;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.Solvers;
import me.ymid.rutea.solver.cvrplib.CvrplibReader;

public final class BenchmarkHarness {

    private static final String USAGE = "uso: BenchmarkHarness <dir-instancias> [--out <dir>]"
            + " [--solver <nombre>] [--seed <n>[,<n>...]] [--time-limit <segundos>] [--iterations <n>] [--warmup]";
    private static final Pattern NODE_COUNT = Pattern.compile("-n(\\d+)");
    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private BenchmarkHarness() {
    }

    public static void main(String[] args) throws IOException {
        main(args, Solvers.all());
    }

    /** Con {@code available} otro módulo agrega solvers que el solver no puede conocer, como Timefold. */
    public static void main(String[] args, List<Solver> available) throws IOException {
        Path instances = null;
        Path out = Path.of("target", "benchmarks");
        List<Solver> solvers = available;
        List<Long> seeds = List.of(1L);
        Duration timeLimit = Duration.ofSeconds(60);
        long iterationLimit = Long.MAX_VALUE;
        boolean warmup = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--out" -> out = Path.of(value(args, ++i));
                    case "--seed" -> seeds = Arrays.stream(value(args, ++i).split(",")).map(String::strip)
                            .map(Long::parseLong).toList();
                    case "--warmup" -> warmup = true;
                    case "--time-limit" -> timeLimit = Duration.ofSeconds(Long.parseLong(value(args, ++i)));
                    case "--iterations" -> iterationLimit = Long.parseLong(value(args, ++i));
                    case "--solver" -> {
                        String name = value(args, ++i);
                        solvers = List.of(available.stream().filter(solver -> solver.name().equals(name))
                                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                        "solver desconocido '" + name + "'; disponibles: "
                                                + available.stream().map(Solver::name).toList())));
                    }
                    default -> {
                        if (args[i].startsWith("--") || instances != null) {
                            throw new IllegalArgumentException("argumento no reconocido: " + args[i]);
                        }
                        instances = Path.of(args[i]);
                    }
                }
            }
            if (instances == null) {
                throw new IllegalArgumentException("falta el directorio de instancias");
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        List<BenchmarkResult> results = run(instances, solvers, seeds, timeLimit, iterationLimit, warmup);
        Path csv = out.resolve("run-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        writeCsv(csv, results, machine());
        printSummary(System.out, results);
        System.out.println();
        System.out.println("CSV: " + csv.toAbsolutePath());
    }

    private static String value(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException(args[index - 1] + " necesita un valor");
        }
        return args[index];
    }

    public static List<BenchmarkResult> run(Path dir, List<Solver> solvers, SolverSettings settings)
            throws IOException {
        return run(dir, solvers, List.of(settings.seed()), settings.timeLimit(), settings.iterationLimit(), false);
    }

    /**
     * Cada solver, en cada instancia, con cada semilla. Con {@code warmup}, antes de medir se resuelve la
     * instancia una vez y se descarta: la corrida medida es "en caliente". Sin él, es "en frío" solo para la
     * primera combinación de la JVM; para un número en frío de verdad, se corre una combinación por proceso.
     */
    public static List<BenchmarkResult> run(Path dir, List<Solver> solvers, List<Long> seeds, Duration timeLimit,
            long iterationLimit, boolean warmup) throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(p -> p.getFileName().toString().endsWith(".vrp"))
                    .sorted(Comparator.comparingInt(BenchmarkHarness::nodeCount)
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList();
        }
        if (files.isEmpty()) {
            throw new IOException("no hay instancias .vrp en " + dir.toAbsolutePath());
        }

        List<BenchmarkResult> results = new ArrayList<>();
        for (Path vrp : files) {
            // Las instancias X traen su .sol (CvrplibDataTest lo exige); las de Suba no tienen BKS publicada.
            Path sol = vrp.resolveSibling(vrp.getFileName().toString().replaceFirst("\\.vrp$", ".sol"));
            OptionalLong bks = Files.exists(sol)
                    ? OptionalLong.of(CvrplibReader.readSolution(sol).cost()) : OptionalLong.empty();
            Instance instance = CvrplibReader.readInstance(vrp);
            for (Solver solver : solvers) {
                for (long seed : seeds) {
                    SolverSettings settings = new SolverSettings(seed, timeLimit, iterationLimit);
                    if (warmup) {
                        solver.solve(instance, settings);
                    }
                    long start = System.nanoTime();
                    Solution solution = solver.solve(instance, settings);
                    Duration wallTime = Duration.ofNanos(System.nanoTime() - start);
                    Evaluation evaluation = Evaluator.evaluate(instance, solution);
                    results.add(new BenchmarkResult(instance.name(), instance.customerCount(), solver.name(), seed,
                            timeLimit, iterationLimit, evaluation.cost(), bks, evaluation.feasible(), wallTime,
                            solution.routeCount(), warmup));
                }
            }
        }
        return results;
    }

    private static int nodeCount(Path file) {
        Matcher matcher = NODE_COUNT.matcher(file.getFileName().toString());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    static void writeCsv(Path csv, List<BenchmarkResult> results, String machine) throws IOException {
        Files.createDirectories(csv.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            writer.write(BenchmarkResult.CSV_HEADER);
            writer.newLine();
            for (BenchmarkResult result : results) {
                writer.write(result.toCsv(machine));
                writer.newLine();
            }
        }
    }

    static void printSummary(PrintStream out, List<BenchmarkResult> results) {
        out.println("| Instancia | n | Solver | Semilla | Costo | BKS | Gap | Tiempo | Vehículos |");
        out.println("|---|---:|---|---:|---:|---:|---:|---:|---:|");
        for (BenchmarkResult r : results) {
            String gap = !r.feasible() ? "infactible" : r.gap().isPresent()
                    ? String.format(Locale.ROOT, "%.2f %%", r.gap().getAsDouble() * 100) : "sin BKS";
            out.printf(Locale.ROOT, "| %s | %d | %s | %d | %d | %s | %s | %.2f ms | %d |%n",
                    r.instance(), r.customers(), r.solver(), r.seed(), r.cost(),
                    r.bestKnownCost().isPresent() ? Long.toString(r.bestKnownCost().getAsLong()) : "—", gap,
                    r.wallTime().toNanos() / 1e6, r.vehicles());
        }
        out.println();
        for (String solver : results.stream().map(BenchmarkResult::solver).distinct().toList()) {
            List<BenchmarkResult> own = results.stream().filter(r -> r.solver().equals(solver)).toList();
            long infeasible = own.stream().filter(r -> !r.feasible()).count();
            List<BenchmarkResult> withGap = own.stream().filter(BenchmarkResult::feasible)
                    .filter(r -> r.gap().isPresent()).toList();
            String excluded = infeasible == 0 ? "" : " (" + infeasible + " infactibles excluidas)";
            if (!withGap.isEmpty()) {
                double meanGap = withGap.stream().mapToDouble(r -> r.gap().getAsDouble()).average().orElseThrow();
                out.printf(Locale.ROOT, "**%s**: gap promedio %.2f %% en %d corridas con BKS%s%n",
                        solver, meanGap * 100, withGap.size(), excluded);
            }
            List<BenchmarkResult> withoutGap = own.stream().filter(BenchmarkResult::feasible)
                    .filter(r -> r.gap().isEmpty()).toList();
            if (!withoutGap.isEmpty()) {
                LongSummaryStatistics costs = withoutGap.stream().mapToLong(BenchmarkResult::cost).summaryStatistics();
                out.printf(Locale.ROOT, "**%s**: costo promedio %.1f (mín. %d, máx. %d) en %d corridas sin BKS%s%n",
                        solver, costs.getAverage(), costs.getMin(), costs.getMax(), withoutGap.size(), excluded);
            }
        }
    }

    static String machine() {
        String cpu = "CPU desconocida";
        Path cpuinfo = Path.of("/proc/cpuinfo");
        if (Files.isReadable(cpuinfo)) {
            try (Stream<String> lines = Files.lines(cpuinfo)) {
                cpu = lines.filter(line -> line.startsWith("model name"))
                        .map(line -> line.substring(line.indexOf(':') + 1).strip())
                        .findFirst().orElse(cpu);
            } catch (IOException e) {
            }
        }
        String machine = cpu + "; " + Runtime.getRuntime().availableProcessors() + " hilos; "
                + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + "; Java " + System.getProperty("java.version");
        return machine.replace(',', ' ');
    }
}
