package me.ymid.rutea.solver.alns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.Solver;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.local.LocalSearch;
import me.ymid.rutea.solver.local.LocalSearchSolver;

/**
 * ALNS (Ropke y Pisinger 2006) sobre la búsqueda local; la remoción por tramos viene de SISR
 * (Christiaens y Vanden Berghe 2020).
 */
public final class AlnsSolver implements Solver {

    static final int SEGMENT = 100;
    static final double REACTION = 0.1;
    static final double SCORE_BEST = 33;
    static final double SCORE_BETTER = 9;
    static final double SCORE_ACCEPTED = 13;
    /**
     * Empeoramiento, como fracción del costo inicial, que se acepta con probabilidad ½ al empezar.
     */
    static final double START_WORSENING = 0.005;
    static final double COOLING = 100;
    static final double BLINK = 0.01;
    static final int MAX_STRING = 10;

    private static final int DESTROY_RANDOM = 0;
    private static final int DESTROY_WORST = 1;
    private static final int DESTROY_RELATED = 2;
    private static final int DESTROY_STRING = 3;
    private static final int REPAIR_GREEDY = 0;
    private static final int REPAIR_REGRET = 1;

    private final int searches;

    public AlnsSolver() {
        this(1);
    }

    /**
     * El número de búsquedas es fijo y no depende de los núcleos de la máquina: si dependiera, el
     * resultado cambiaría de una máquina a otra.
     */
    public AlnsSolver(int searches) {
        if (searches < 1) {
            throw new IllegalArgumentException("se necesita al menos una búsqueda, son " + searches);
        }
        this.searches = searches;
    }

    @Override
    public String name() {
        return searches == 1 ? "alns" : "alns-x" + searches;
    }

    @Override
    public Solution solve(Instance instance, SolverSettings settings) {
        long start = System.nanoTime();
        long deadline = settings.deadlineFromNow();
        Solution initial = new LocalSearchSolver().solve(instance, settings);
        if (instance.customerCount() < 2) {
            return initial;
        }
        int[][] closest = closest(instance);
        LocalSearch localSearch = new LocalSearch(instance);
        if (searches == 1) {
            return new Search(instance, settings, settings.seed(), start, deadline, closest, localSearch)
                    .run(initial).solution();
        }
        int threads = Math.min(searches, Runtime.getRuntime().availableProcessors());
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Result>> runs = new ArrayList<>();
            for (int k = 0; k < searches; k++) {
                // La búsqueda 0 usa la semilla de la corrida: con las mismas iteraciones, nunca queda
                // peor que el ALNS de una sola búsqueda
                Search search = new Search(instance, settings, settings.seed() + k, start, deadline, closest,
                        localSearch);
                runs.add(pool.submit(() -> search.run(initial)));
            }
            Result best = null;
            for (Future<Result> run : runs) {
                Result result = run.get();
                if (best == null || result.cost() < best.cost()) {
                    best = result;
                }
            }
            return best.solution();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ALNS interrumpido", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("falló una búsqueda de ALNS", e.getCause());
        }
    }

    private record Result(Solution solution, long cost) {
    }

    static int[][] closest(Instance instance) {
        int size = instance.size();
        int[][] closest = new int[size][];
        long[] keys = new long[size - 2];
        for (int u = 1; u < size; u++) {
            int k = 0;
            for (int v = 1; v < size; v++) {
                if (v != u) {
                    long closeness = Math.min(instance.distance(u, v), instance.distance(v, u));
                    keys[k++] = closeness << 32 | v;
                }
            }
            Arrays.sort(keys);
            closest[u] = new int[k];
            for (int i = 0; i < k; i++) {
                closest[u][i] = (int) keys[i];
            }
        }
        return closest;
    }

    private static final class Search {

        private final Instance instance;
        private final SolverSettings settings;
        private final long start;
        private final long deadline;
        private final Random random;
        private final LocalSearch localSearch;
        private final int[][] closest;
        private final int customers;
        private final int minRemoved;
        private final int maxRemoved;

        private final double[] destroyWeights = {1, 1, 1, 1};
        private final double[] repairWeights = {1, 1};
        private final double[] destroyScores = new double[4];
        private final double[] repairScores = new double[2];
        private final int[] destroyUses = new int[4];
        private final int[] repairUses = new int[2];

        private final boolean[] removed;
        private final int[] removedList;
        private final int[] routeOf;
        private final int[] positionOf;
        private final boolean[] touched;
        private final long[] keys;

        Search(Instance instance, SolverSettings settings, long seed, long start, long deadline, int[][] closest,
                LocalSearch localSearch) {
            this.instance = instance;
            this.settings = settings;
            this.start = start;
            this.deadline = deadline;
            this.random = new Random(seed);
            this.localSearch = localSearch;
            this.closest = closest;
            this.customers = instance.customerCount();
            this.minRemoved = Math.min(customers, 4);
            this.maxRemoved = Math.min(customers, Math.max(minRemoved, Math.min(60, customers * 3 / 10)));
            this.removed = new boolean[instance.size()];
            this.removedList = new int[customers];
            this.routeOf = new int[instance.size()];
            this.positionOf = new int[instance.size()];
            this.touched = new boolean[customers];
            this.keys = new long[customers];
        }

        Result run(Solution initial) {
            Plan current = new Plan(instance, initial);
            Plan candidate = current.copy();
            Solution best = initial;
            long bestCost = current.cost();
            double startTemperature = START_WORSENING * current.cost() / Math.log(2);

            for (long iteration = 0; iteration < settings.iterationLimit(); iteration++) {
                long now = System.nanoTime();
                if (now >= deadline) {
                    break;
                }
                double temperature = startTemperature * Math.pow(1 / COOLING, progress(iteration, now));
                int destroy = roulette(destroyWeights);
                int repair = roulette(repairWeights);

                current.copyInto(candidate);
                int count = destroy(destroy, candidate);
                candidate.remove(removed);
                repair(repair, candidate, count);
                Solution polished = localSearch.improve(candidate.toSolution(), random, deadline);
                candidate.assign(polished);

                long delta = candidate.cost() - current.cost();
                double score = 0;
                if (candidate.cost() < bestCost) {
                    best = polished;
                    bestCost = candidate.cost();
                    score = SCORE_BEST;
                } else if (delta < 0) {
                    score = SCORE_BETTER;
                } else if (delta > 0 && random.nextDouble() < Math.exp(-delta / temperature)) {
                    score = SCORE_ACCEPTED;
                }
                if (delta < 0 || score > 0) {
                    Plan swap = current;
                    current = candidate;
                    candidate = swap;
                }
                destroyScores[destroy] += score;
                repairScores[repair] += score;
                destroyUses[destroy]++;
                repairUses[repair]++;
                if ((iteration + 1) % SEGMENT == 0) {
                    adapt(destroyWeights, destroyScores, destroyUses);
                    adapt(repairWeights, repairScores, repairUses);
                }
            }
            return new Result(best, bestCost);
        }

        /**
         * Por iteraciones si hay límite de iteraciones: así la temperatura, y con ella la corrida,
         * no depende del reloj.
         */
        private double progress(long iteration, long now) {
            if (settings.hasIterationLimit()) {
                return (double) iteration / settings.iterationLimit();
            }
            return deadline == Long.MAX_VALUE ? 0 : Math.min(1, (double) (now - start) / (deadline - start));
        }

        private int roulette(double[] weights) {
            double total = 0;
            for (double weight : weights) {
                total += weight;
            }
            double pick = random.nextDouble() * total;
            for (int i = 0; i < weights.length - 1; i++) {
                pick -= weights[i];
                if (pick < 0) {
                    return i;
                }
            }
            return weights.length - 1;
        }

        private static void adapt(double[] weights, double[] scores, int[] uses) {
            for (int i = 0; i < weights.length; i++) {
                if (uses[i] > 0) {
                    weights[i] = (1 - REACTION) * weights[i] + REACTION * scores[i] / uses[i];
                }
                scores[i] = 0;
                uses[i] = 0;
            }
        }

        private int destroy(int operator, Plan plan) {
            Arrays.fill(removed, false);
            int target = minRemoved + random.nextInt(maxRemoved - minRemoved + 1);
            locate(plan);
            int count = switch (operator) {
                case DESTROY_RANDOM -> randomRemoval(target);
                case DESTROY_WORST -> worstRemoval(plan, target);
                case DESTROY_RELATED -> relatedRemoval(target);
                case DESTROY_STRING -> stringRemoval(plan, target);
                default -> throw new IllegalStateException("operador " + operator);
            };
            return count;
        }

        private int mark(int customer, int count) {
            removed[customer] = true;
            removedList[count] = customer;
            return count + 1;
        }

        private int randomRemoval(int target) {
            int count = 0;
            while (count < target) {
                int customer = 1 + random.nextInt(customers);
                if (!removed[customer]) {
                    count = mark(customer, count);
                }
            }
            return count;
        }

        /**
         * Sesgo y³: casi siempre sale uno de los primeros de la lista, pero no siempre el mismo.
         */
        private int worstRemoval(Plan plan, int target) {
            int k = 0;
            for (int r = 0; r < plan.routeCount(); r++) {
                for (int p = 0; p < plan.length(r); p++) {
                    int c = plan.customer(r, p);
                    int a = plan.before(r, p);
                    int b = plan.after(r, p);
                    long gain = (long) instance.distance(a, c) + instance.distance(c, b) - instance.distance(a, b);
                    keys[k++] = -gain << 32 | c;
                }
            }
            Arrays.sort(keys, 0, k);
            int count = 0;
            int available = k;
            while (count < target) {
                double y = random.nextDouble();
                int index = (int) (y * y * y * available);
                int customer = (int) keys[index];
                System.arraycopy(keys, index + 1, keys, index, available - index - 1);
                available--;
                count = mark(customer, count);
            }
            return count;
        }

        private int relatedRemoval(int target) {
            int count = mark(1 + random.nextInt(customers), 0);
            while (count < target) {
                int reference = removedList[random.nextInt(count)];
                double y = random.nextDouble();
                int available = customers - count;
                int index = (int) (Math.pow(y, 6) * available);
                for (int candidate : closest[reference]) {
                    if (!removed[candidate] && index-- == 0) {
                        count = mark(candidate, count);
                        break;
                    }
                }
            }
            return count;
        }

        private int stringRemoval(Plan plan, int target) {
            Arrays.fill(touched, 0, plan.routeCount(), false);
            int seed = 1 + random.nextInt(customers);
            int count = 0;
            for (int i = -1; i < closest[seed].length && count < target; i++) {
                int customer = i < 0 ? seed : closest[seed][i];
                int route = routeOf[customer];
                if (removed[customer] || touched[route]) {
                    continue;
                }
                touched[route] = true;
                int length = plan.length(route);
                int span = 1 + random.nextInt(Math.min(Math.min(MAX_STRING, length), target - count));
                int first = Math.clamp(positionOf[customer] - random.nextInt(span), 0, length - span);
                for (int p = first; p < first + span; p++) {
                    count = mark(plan.customer(route, p), count);
                }
            }
            return count;
        }

        private void locate(Plan plan) {
            for (int r = 0; r < plan.routeCount(); r++) {
                for (int p = 0; p < plan.length(r); p++) {
                    routeOf[plan.customer(r, p)] = r;
                    positionOf[plan.customer(r, p)] = p;
                }
            }
        }

        private void repair(int operator, Plan plan, int count) {
            switch (operator) {
                case REPAIR_GREEDY -> greedy(plan, count);
                case REPAIR_REGRET -> regret(plan, count);
                default -> throw new IllegalStateException("operador " + operator);
            }
        }

        private void greedy(Plan plan, int count) {
            shuffle(removedList, count);
            for (int i = 0; i < count; i++) {
                int customer = removedList[i];
                int bestRoute = plan.emptyRoute();
                int bestPosition = 0;
                long bestCost = plan.insertionCost(customer, bestRoute, 0);
                for (int r = 0; r < plan.routeCount(); r++) {
                    if (plan.length(r) == 0 || !plan.fits(customer, r)) {
                        continue;
                    }
                    for (int p = 0; p <= plan.length(r); p++) {
                        if (random.nextDouble() < BLINK) {
                            continue;
                        }
                        long cost = plan.insertionCost(customer, r, p);
                        if (cost < bestCost) {
                            bestCost = cost;
                            bestRoute = r;
                            bestPosition = p;
                        }
                    }
                }
                plan.insert(customer, bestRoute, bestPosition);
            }
        }

        /** Abrir una ruta nueva cuenta como una opción más. */
        private void regret(Plan plan, int count) {
            int pending = count;
            while (pending > 0) {
                int chosen = -1;
                long chosenRegret = Long.MIN_VALUE;
                long chosenCost = Long.MAX_VALUE;
                int chosenRoute = 0;
                int chosenPosition = 0;
                for (int i = 0; i < pending; i++) {
                    int customer = removedList[i];
                    int empty = plan.emptyRoute();
                    long first = plan.insertionCost(customer, empty, 0);
                    long second = Long.MAX_VALUE;
                    int firstRoute = empty;
                    int firstPosition = 0;
                    for (int r = 0; r < plan.routeCount(); r++) {
                        if (plan.length(r) == 0 || !plan.fits(customer, r)) {
                            continue;
                        }
                        long routeBest = Long.MAX_VALUE;
                        int routePosition = 0;
                        for (int p = 0; p <= plan.length(r); p++) {
                            long cost = plan.insertionCost(customer, r, p);
                            if (cost < routeBest) {
                                routeBest = cost;
                                routePosition = p;
                            }
                        }
                        if (routeBest < first) {
                            second = first;
                            first = routeBest;
                            firstRoute = r;
                            firstPosition = routePosition;
                        } else if (routeBest < second) {
                            second = routeBest;
                        }
                    }
                    long regret = second == Long.MAX_VALUE ? Long.MAX_VALUE : second - first;
                    if (regret > chosenRegret || (regret == chosenRegret && first < chosenCost)) {
                        chosen = i;
                        chosenRegret = regret;
                        chosenCost = first;
                        chosenRoute = firstRoute;
                        chosenPosition = firstPosition;
                    }
                }
                plan.insert(removedList[chosen], chosenRoute, chosenPosition);
                removedList[chosen] = removedList[--pending];
            }
        }

        private void shuffle(int[] values, int count) {
            for (int i = count - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int swap = values[i];
                values[i] = values[j];
                values[j] = swap;
            }
        }
    }
}
