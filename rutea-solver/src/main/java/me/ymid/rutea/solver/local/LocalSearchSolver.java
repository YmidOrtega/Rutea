package me.ymid.rutea.solver.local;

import java.util.Arrays;
import java.util.Random;

import me.ymid.rutea.solver.ClarkeWrightSolver;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.Solver;
import me.ymid.rutea.solver.SolverSettings;

/**
 * Clarke-Wright seguido de búsqueda local hasta un óptimo local: aplica cualquier movimiento que
 * baje el costo y se detiene cuando ninguno lo hace o se acaba el tiempo.
 *
 * <p>Movimientos, todos válidos con distancias asimétricas:
 * <ul>
 *   <li><b>relocate</b> y <b>Or-opt</b>: mover un tramo de uno a {@value #MAX_SEGMENT} clientes antes
 *       o después de otro cliente, en la misma ruta o en otra, en su sentido o invertido;</li>
 *   <li><b>swap</b>: intercambiar dos clientes de rutas distintas;</li>
 *   <li><b>2-opt</b>: invertir un tramo de una ruta; el costo del tramo invertido sale de los
 *       acumulados al revés de {@link Routes}, no de suponer que ida y vuelta cuestan igual;</li>
 *   <li><b>2-opt*</b>: cruzar las colas de dos rutas, sin invertir nada; si las dos colas que se
 *       cruzan arrancan en el depósito, une dos rutas en una.</li>
 * </ul>
 *
 * <p>Granular: solo se prueban movimientos que acercan a cada cliente a uno de sus
 * {@value #NEIGHBORS} vecinos más cercanos, porque unir clientes lejanos casi nunca mejora.
 * La semilla fija el orden en que se recorren los clientes; con la misma semilla la solución es la
 * misma, salvo que el límite de tiempo corte la búsqueda antes del óptimo local.
 */
public final class LocalSearchSolver implements Solver {

    static final int NEIGHBORS = 30;

    /** Valores de λ de Clarke-Wright desde los que arranca la búsqueda; el primero es el original. */
    static final double[] SHAPES = {1.0, 0.6, 0.8, 1.2, 1.4, 1.6, 1.8, 2.0};

    /** Tramo más largo que mueve Or-opt. */
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
     * Los {@value #NEIGHBORS} clientes más cercanos a cada cliente. Con distancias asimétricas,
     * cercano es lo más corto entre ir y volver: los movimientos pueden poner el arco en
     * cualquiera de los dos sentidos.
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

    /** Una corrida de búsqueda local sobre {@code routes}, que modifica en sitio. */
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

        /**
         * Mueve el tramo de {@code length} clientes que empieza en {@code u} justo después
         * ({@code after}) o justo antes de {@code v}, opcionalmente invertido. Con un solo cliente
         * es el relocate clásico; con dos o tres, Or-opt.
         */
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

        /** Intercambia {@code u} y {@code v}, que están en rutas distintas. */
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

        /**
         * Cruza colas: la ruta de {@code u} sigue hasta {@code u} y continúa desde {@code v}; la de
         * {@code v} sigue hasta antes de {@code v} y continúa con lo que venía después de {@code u}.
         */
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

        /** Invierte el tramo entre {@code u} y {@code v}, que están en la misma ruta. */
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

        /** Invierte {@code values[lo..hi]}, ambos inclusive. */
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

        /** Los primeros {@code firstEnd} de {@code first} seguidos de {@code second} desde {@code secondFrom}. */
        private static int[] concat(int[] first, int firstEnd, int[] second, int secondFrom) {
            int[] result = new int[firstEnd + second.length - secondFrom];
            System.arraycopy(first, 0, result, 0, firstEnd);
            System.arraycopy(second, secondFrom, result, firstEnd, second.length - secondFrom);
            return result;
        }
    }
}
