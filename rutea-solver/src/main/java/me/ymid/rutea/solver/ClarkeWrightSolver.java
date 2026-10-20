package me.ymid.rutea.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ahorros de Clarke-Wright, versión paralela. Con distancias asimétricas una ruta nunca se
 * invierte, porque eso cambiaría su costo; solo con matriz simétrica se invierte para unir por
 * cualquier extremo. λ (Gaskell, Yellow) pondera d(i,j) en el ahorro; variarlo da arranques
 * distintos a la búsqueda local.
 */
public final class ClarkeWrightSolver implements Solver {

    private static final int DEPOT = 0;

    private final double shape;

    public ClarkeWrightSolver() {
        this(1);
    }

    public ClarkeWrightSolver(double shape) {
        if (!(shape > 0) || Double.isInfinite(shape)) {
            throw new IllegalArgumentException("λ debe ser positivo y finito, es " + shape);
        }
        this.shape = shape;
    }

    @Override
    public String name() {
        return "clarke-wright";
    }

    @Override
    public Solution solve(Instance instance, SolverSettings settings) {
        int size = instance.size();
        boolean symmetric = instance.distances().isSymmetric();

        int[] next = new int[size];
        int[] prev = new int[size];
        int[] routeOf = new int[size];
        int[] first = new int[size];
        int[] last = new int[size];
        int[] load = new int[size];
        for (int c = 1; c < size; c++) {
            routeOf[c] = c;
            first[c] = c;
            last[c] = c;
            load[c] = instance.demand(c);
        }

        for (long key : savings(instance, shape)) {
            int i = (int) (key >>> 16) & 0xFFFF;
            int j = (int) key & 0xFFFF;
            int a = routeOf[i];
            int b = routeOf[j];
            if (a == b || load[a] + load[b] > instance.capacity()) {
                continue;
            }
            if (last[a] != i) {
                if (!symmetric || first[a] != i) {
                    continue;
                }
                reverse(a, next, prev, first, last);
            }
            if (first[b] != j) {
                if (!symmetric || last[b] != j) {
                    continue;
                }
                reverse(b, next, prev, first, last);
            }
            next[i] = j;
            prev[j] = i;
            last[a] = last[b];
            load[a] += load[b];
            for (int c = j; c != DEPOT; c = next[c]) {
                routeOf[c] = a;
            }
        }

        List<int[]> routes = new ArrayList<>();
        int[] route = new int[size - 1];
        for (int c = 1; c < size; c++) {
            if (prev[c] != DEPOT) {
                continue;
            }
            int length = 0;
            for (int v = c; v != DEPOT; v = next[v]) {
                route[length++] = v;
            }
            routes.add(Arrays.copyOf(route, length));
        }
        return new Solution(routes.toArray(int[][]::new));
    }

    /**
     * Cada par va en un long para ordenar sin objetos: arriba los bits del ahorro como float, que
     * crecen con el valor si es positivo, complementados para que el mayor quede primero; abajo i y
     * j en 16 bits, que desempatan por menor índice.
     */
    private static long[] savings(Instance instance, double shape) {
        int size = instance.size();
        long[] keys = new long[(size - 1) * (size - 2)];
        int count = 0;
        for (int i = 1; i < size; i++) {
            int back = instance.distance(i, DEPOT);
            for (int j = 1; j < size; j++) {
                if (i == j) {
                    continue;
                }
                float saving = (float) ((double) back + instance.distance(DEPOT, j) - shape * instance.distance(i, j));
                if (saving > 0) {
                    long rank = Integer.MAX_VALUE - Float.floatToRawIntBits(saving);
                    keys[count++] = rank << 32 | (long) i << 16 | j;
                }
            }
        }
        long[] sorted = Arrays.copyOf(keys, count);
        Arrays.sort(sorted);
        return sorted;
    }

    private static void reverse(int r, int[] next, int[] prev, int[] first, int[] last) {
        int c = first[r];
        while (c != DEPOT) {
            int following = next[c];
            next[c] = prev[c];
            prev[c] = following;
            c = following;
        }
        int oldFirst = first[r];
        first[r] = last[r];
        last[r] = oldFirst;
    }
}
