package me.ymid.rutea.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Algoritmo de ahorros de Clarke y Wright, versión paralela: arranca con una ruta por cliente y
 * une rutas por sus extremos en orden de ahorro decreciente, mientras la carga quepa.
 *
 * <p>Unir el final de la ruta de {@code i} con el inicio de la de {@code j} ahorra
 * {@code d(i,0) + d(0,j) - d(i,j)}: el camión deja de volver al depósito desde {@code i} y de
 * salir de él hacia {@code j}. Con distancias asimétricas (sentidos viales) el ahorro depende del
 * sentido, así que se calcula para cada par ordenado y una ruta nunca se invierte: invertirla
 * cambiaría su costo. Solo si la matriz es simétrica se permite invertir una ruta para que
 * {@code i} quede al final o {@code j} al inicio, como en el algoritmo original.
 *
 * <p>Con un parámetro de forma {@code λ} el ahorro pasa a ser {@code d(i,0) + d(0,j) - λ·d(i,j)}
 * (Gaskell, Yellow): un λ mayor castiga unir clientes lejanos entre sí y da rutas más compactas.
 * El original es {@code λ = 1}. Variar λ da puntos de partida distintos para la búsqueda local.
 *
 * <p>Es determinista (los empates de ahorro van al par de menor índice) e ignora la semilla y el
 * límite de tiempo. Costo O(n² log n) por el ordenamiento de los ahorros.
 */
public final class ClarkeWrightSolver implements Solver {

    /** Nodo nulo en {@code next} y {@code prev}: el vecino es el depósito. */
    private static final int DEPOT = 0;

    private final double shape;

    /** El algoritmo original, {@code λ = 1}. */
    public ClarkeWrightSolver() {
        this(1);
    }

    /** @param shape λ, el peso de {@code d(i,j)} en el ahorro; positivo */
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

        // Cada cliente arranca en su propia ruta, identificada por el propio cliente
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
            // Cada ruta se emite una sola vez, desde el cliente que sale del depósito
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
     * Pares ordenados {@code (i, j)} de clientes con ahorro positivo, del mayor al menor.
     * Cada par va empaquetado en un {@code long} para ordenar un arreglo primitivo sin objetos: en
     * los 32 bits altos el ahorro como {@code float}, cuyos bits crecen con el valor cuando es
     * positivo, complementado para que el orden ascendente ponga primero el mayor; abajo {@code i}
     * y {@code j} en 16 bits cada uno, que desempatan a favor del par de menor índice. Una matriz de
     * más de 65 536 nodos no cabría en memoria, así que 16 bits alcanzan. Con λ = 1 y distancias
     * menores a 2²⁴ el {@code float} es exacto.
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

    /** Invierte la ruta {@code r} en sitio. Solo es válido con distancias simétricas. */
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
