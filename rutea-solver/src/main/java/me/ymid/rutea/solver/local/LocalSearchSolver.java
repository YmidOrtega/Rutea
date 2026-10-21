package me.ymid.rutea.solver.local;

import java.util.Arrays;
import java.util.Random;

import me.ymid.rutea.solver.ClarkeWrightSolver;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.Solver;
import me.ymid.rutea.solver.SolverSettings;

/**
 * Clarke-Wright con varios λ y búsqueda local granular hasta un óptimo local. Todos los movimientos
 * son exactos con distancias asimétricas.
 */
public final class LocalSearchSolver implements Solver {

    static final int NEIGHBORS = 30;

    static final double[] SHAPES = {1.0, 0.6, 0.8, 1.2, 1.4, 1.6, 1.8, 2.0};

    static final int MAX_SEGMENT = 3;

    @Override
    public String name() {
        return "local-search";
    }

    @Override
    public Solution solve(Instance instance, SolverSettings settings) {
        long deadline = settings.deadlineFromNow();
        int[][] neighbors = neighbors(instance);
        Random random = new Random(settings.seed());
        Solution best = null;
        long bestCost = Long.MAX_VALUE;
        for (double shape : SHAPES) {
            Routes routes = new Routes(instance, new ClarkeWrightSolver(shape).solve(instance, settings));
            new Search(instance, routes, neighbors).run(random, deadline);
            if (routes.cost() < bestCost) {
                bestCost = routes.cost();
                best = routes.toSolution();
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        return best;
    }

    /**
     * Con distancias asimétricas, cercano es lo más corto entre ir y volver: un movimiento puede
     * poner el arco en cualquiera de los dos sentidos.
     */
    static int[][] neighbors(Instance instance) {
        int size = instance.size();
        int count = Math.min(NEIGHBORS, size - 2);
        int[][] neighbors = new int[size][];
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
            neighbors[u] = new int[count];
            for (int i = 0; i < count; i++) {
                neighbors[u][i] = (int) keys[i];
            }
        }
        return neighbors;
    }

    static final class Search {

        private final Instance instance;
        private final Routes routes;
        private final int[][] neighbors;
        private final int capacity;

        Search(Instance instance, Routes routes, int[][] neighbors) {
            this.instance = instance;
            this.routes = routes;
            this.neighbors = neighbors;
            this.capacity = instance.capacity();
        }

        void run(Random random, long deadline) {
            int[] order = new int[instance.customerCount()];
            Arrays.setAll(order, i -> i + 1);
            boolean improved = true;
            while (improved) {
                improved = false;
                shuffle(order, random);
                for (int u : order) {
                    if (System.nanoTime() >= deadline) {
                        return;
                    }
                    for (int v : neighbors[u]) {
                        if (improve(u, v)) {
                            improved = true;
                        }
                    }
                }
            }
        }

        private boolean improve(int u, int v) {
            for (int length = 1; length <= MAX_SEGMENT; length++) {
                if (relocate(u, v, length, true, false) || relocate(u, v, length, false, false)) {
                    return true;
                }
                if (length > 1 && (relocate(u, v, length, true, true) || relocate(u, v, length, false, true))) {
                    return true;
                }
            }
            if (routes.routeOf(u) != routes.routeOf(v)) {
                return swap(u, v) || twoOptStar(u, v);
            }
            return twoOpt(u, v);
        }

        private boolean relocate(int u, int v, int length, boolean after, boolean reversed) {
            int ru = routes.routeOf(u);
            int rv = routes.routeOf(v);
            int pu = routes.position(u);
            int pe = pu + length - 1;
            if (pe > routes.length(ru)) {
                return false;
            }
            // Índice entre los clientes de rv antes del cual se inserta, contado antes de quitar el tramo
            int at = after ? routes.position(v) : routes.position(v) - 1;
            if (ru == rv && at >= pu - 1 && at <= pe) {
                return false;
            }
            int segmentLoad = routes.loadUpTo(ru, pe) - routes.loadUpTo(ru, pu - 1);
            if (ru != rv && routes.load(rv) + segmentLoad > capacity) {
                return false;
            }
            int e = routes.node(ru, pe);
            int a = routes.node(ru, pu - 1);
            int b = routes.node(ru, pe + 1);
            int x = routes.node(rv, at);
            int y = routes.node(rv, at + 1);
            long delta = d(a, b) - d(a, u) - d(e, b) - d(x, y) + (reversed
                    ? d(x, e) + d(u, y) + routes.backwardCost(ru, pu, pe) - routes.forwardCost(ru, pu, pe)
                    : d(x, u) + d(e, y));
            if (delta >= 0) {
                return false;
            }
            long before = routes.cost();
            int[] source = routes.customers(ru);
            int[] segment = Arrays.copyOfRange(source, pu - 1, pe);
            if (reversed) {
                reverse(segment, 0, segment.length - 1);
            }
            int[] without = remove(source, pu - 1, length);
            if (ru == rv) {
                routes.set(ru, insert(without, at > pe - 1 ? at - length : at, segment));
            } else {
                routes.set(ru, without);
                routes.set(rv, insert(routes.customers(rv), at, segment));
            }
            assert routes.cost() == before + delta : "el delta " + delta + " no coincide con el costo real";
            return true;
        }

        private boolean swap(int u, int v) {
            int ru = routes.routeOf(u);
            int rv = routes.routeOf(v);
            int change = instance.demand(v) - instance.demand(u);
            if (routes.load(ru) + change > capacity || routes.load(rv) - change > capacity) {
                return false;
            }
            int a = routes.previous(u);
            int b = routes.next(u);
            int x = routes.previous(v);
            int y = routes.next(v);
            long delta = d(a, v) + d(v, b) - d(a, u) - d(u, b) + d(x, u) + d(u, y) - d(x, v) - d(v, y);
            if (delta >= 0) {
                return false;
            }
            long before = routes.cost();
            int[] first = routes.customers(ru);
            int[] second = routes.customers(rv);
            first[routes.position(u) - 1] = v;
            second[routes.position(v) - 1] = u;
            routes.set(ru, first);
            routes.set(rv, second);
            assert routes.cost() == before + delta : "el delta " + delta + " no coincide con el costo real";
            return true;
        }

        private boolean twoOptStar(int u, int v) {
            int ru = routes.routeOf(u);
            int rv = routes.routeOf(v);
            int pu = routes.position(u);
            int pv = routes.position(v);
            int headU = routes.loadUpTo(ru, pu);
            int headV = routes.loadUpTo(rv, pv - 1);
            if (headU + routes.load(rv) - headV > capacity || headV + routes.load(ru) - headU > capacity) {
                return false;
            }
            int b = routes.next(u);
            int x = routes.previous(v);
            long delta = d(u, v) + d(x, b) - d(u, b) - d(x, v);
            if (delta >= 0) {
                return false;
            }
            long before = routes.cost();
            int[] first = routes.customers(ru);
            int[] second = routes.customers(rv);
            routes.set(ru, concat(first, pu, second, pv - 1));
            routes.set(rv, concat(second, pv - 1, first, pu));
            assert routes.cost() == before + delta : "el delta " + delta + " no coincide con el costo real";
            return true;
        }

        private boolean twoOpt(int u, int v) {
            int r = routes.routeOf(u);
            int i = Math.min(routes.position(u), routes.position(v));
            int j = Math.max(routes.position(u), routes.position(v));
            if (j == i + 1) {
                return false;
            }
            // Pasa de s → t … w → z a s → w … t → z
            int s = routes.node(r, i);
            int t = routes.node(r, i + 1);
            int w = routes.node(r, j);
            int z = routes.node(r, j + 1);
            long delta = d(s, w) + d(t, z) - d(s, t) - d(w, z)
                    + routes.backwardCost(r, i + 1, j) - routes.forwardCost(r, i + 1, j);
            if (delta >= 0) {
                return false;
            }
            long before = routes.cost();
            int[] customers = routes.customers(r);
            reverse(customers, i, j - 1);
            routes.set(r, customers);
            assert routes.cost() == before + delta : "el delta " + delta + " no coincide con el costo real";
            return true;
        }

        private long d(int from, int to) {
            return instance.distance(from, to);
        }

        private static void shuffle(int[] values, Random random) {
            for (int i = values.length - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int swap = values[i];
                values[i] = values[j];
                values[j] = swap;
            }
        }

        private static void reverse(int[] values, int lo, int hi) {
            for (; lo < hi; lo++, hi--) {
                int swap = values[lo];
                values[lo] = values[hi];
                values[hi] = swap;
            }
        }

        private static int[] remove(int[] values, int index, int count) {
            int[] result = new int[values.length - count];
            System.arraycopy(values, 0, result, 0, index);
            System.arraycopy(values, index + count, result, index, values.length - index - count);
            return result;
        }

        private static int[] insert(int[] values, int index, int[] inserted) {
            int[] result = new int[values.length + inserted.length];
            System.arraycopy(values, 0, result, 0, index);
            System.arraycopy(inserted, 0, result, index, inserted.length);
            System.arraycopy(values, index, result, index + inserted.length, values.length - index);
            return result;
        }

        private static int[] concat(int[] first, int firstEnd, int[] second, int secondFrom) {
            int[] result = new int[firstEnd + second.length - secondFrom];
            System.arraycopy(first, 0, result, 0, firstEnd);
            System.arraycopy(second, secondFrom, result, firstEnd, second.length - secondFrom);
            return result;
        }
    }
}
