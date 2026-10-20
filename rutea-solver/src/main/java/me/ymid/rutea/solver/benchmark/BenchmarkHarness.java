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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
import me.ymid.rutea.solver.cvrplib.BestKnownSolution;
import me.ymid.rutea.solver.cvrplib.CvrplibReader;

public final class BenchmarkHarness {

    private static final String USAGE = "uso: BenchmarkHarness <dir-instancias> [--out <dir>]"
            + " [--solver <nombre>] [--seed <n>] [--time-limit <segundos>] [--iterations <n>]";
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
        long seed = 1;
        Duration timeLimit = Duration.ofSeconds(60);
        long iterationLimit = Long.MAX_VALUE;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--out" -> out = Path.of(value(args, ++i));
                    case "--seed" -> seed = Long.parseLong(value(args, ++i));
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

        List<BenchmarkResult> results = run(instances, solvers, new SolverSettings(seed, timeLimit, iterationLimit));
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
            Path sol = vrp.resolveSibling(vrp.getFileName().toString().replaceFirst("\\.vrp$", ".sol"));
            if (!Files.exists(sol)) {
                throw new IOException("falta la BKS de " + vrp.getFileName() + ": se esperaba " + sol.getFileName());
            }
            Instance instance = CvrplibReader.readInstance(vrp);
            BestKnownSolution bks = CvrplibReader.readSolution(sol);
            for (Solver solver : solvers) {
                long start = System.nanoTime();
                Solution solution = solver.solve(instance, settings);
                Duration wallTime = Duration.ofNanos(System.nanoTime() - start);
                Evaluation evaluation = Evaluator.evaluate(instance, solution);
                results.add(new BenchmarkResult(instance.name(), instance.customerCount(), solver.name(),
                        settings.seed(), settings.timeLimit(), settings.iterationLimit(), evaluation.cost(), bks.cost(),
                        evaluation.feasible(), wallTime, solution.routeCount()));
            }
        }
        return results;
    }

    private static int nodeCount(Path file) {
        Matcher matcher = NODE_COUNT.matcher(file.getFileName().toString());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    /** Nunca pisa una corrida anterior: si el archivo ya existe, falla. */
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
        out.println("| Instancia | n | Solver | Costo | BKS | Gap | Tiempo | Vehículos |");
        out.println("|---|---:|---|---:|---:|---:|---:|---:|");
        for (BenchmarkResult r : results) {
            out.printf(Locale.ROOT, "| %s | %d | %s | %d | %d | %s | %.2f ms | %d |%n",
                    r.instance(), r.customers(), r.solver(), r.cost(), r.bestKnownCost(),
                    r.feasible() ? String.format(Locale.ROOT, "%.2f %%", r.gap() * 100) : "infactible",
                    r.wallTime().toNanos() / 1e6, r.vehicles());
        }
        out.println();
        for (String solver : results.stream().map(BenchmarkResult::solver).distinct().toList()) {
            List<BenchmarkResult> own = results.stream().filter(r -> r.solver().equals(solver)).toList();
            long infeasible = own.stream().filter(r -> !r.feasible()).count();
            double meanGap = own.stream().filter(BenchmarkResult::feasible)
                    .mapToDouble(BenchmarkResult::gap).average().orElse(Double.NaN);
            out.printf(Locale.ROOT, "**%s**: gap promedio %.2f %% en %d instancias%s%n",
                    solver, meanGap * 100, own.size() - infeasible,
                    infeasible == 0 ? "" : " (" + infeasible + " infactibles excluidas)");
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
