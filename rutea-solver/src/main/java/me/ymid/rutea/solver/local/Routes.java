package me.ymid.rutea.solver.local;

import java.util.Arrays;

import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;

/**
 * Rutas modificables durante la búsqueda, con los acumulados que permiten evaluar un movimiento
 * en O(1).
 *
 * <p>Cada ruta se guarda con el depósito en los dos extremos: la posición {@code 0} es la salida y
 * la {@code length + 1} la vuelta. Por posición se acumulan la carga, el costo de recorrerla hacia
 * adelante y el de recorrerla al revés; con este último, invertir un tramo cuesta lo mismo de
 * calcular con distancias simétricas que asimétricas.
 *
 * <p>Evaluar no reserva memoria. Aplicar un movimiento reconstruye solo las rutas que toca, en
 * O(longitud de la ruta).
 */
final class Routes {

    private final Instance instance;
    private final int[][] nodes;
    private final int[][] load;
    private final long[][] forward;
    private final long[][] backward;
    private final int[] routeOf;
    private final int[] position;

    Routes(Instance instance, Solution start) {
        this.instance = instance;
        int count = start.routeCount();
        nodes = new int[count][];
        load = new int[count][];
        forward = new long[count][];
        backward = new long[count][];
        routeOf = new int[instance.size()];
        position = new int[instance.size()];
        for (int r = 0; r < count; r++) {
            set(r, start.route(r));
        }
    }

    int routeOf(int customer) {
        return routeOf[customer];
    }

    /** Posición del cliente en su ruta, de {@code 1} a {@code length}. */
    int position(int customer) {
        return position[customer];
    }

    /** Número de clientes de la ruta. */
    int length(int route) {
        return nodes[route].length - 2;
    }

    int node(int route, int position) {
        return nodes[route][position];
    }

    int previous(int customer) {
        return nodes[routeOf[customer]][position[customer] - 1];
    }

    int next(int customer) {
        return nodes[routeOf[customer]][position[customer] + 1];
    }

    /** Carga desde la salida hasta {@code position}, inclusive. */
    int loadUpTo(int route, int position) {
        return load[route][position];
    }

    int load(int route) {
        return load[route][nodes[route].length - 1];
    }

    /** Costo de recorrer de la posición {@code from} a la {@code to} hacia adelante. */
    long forwardCost(int route, int from, int to) {
        return forward[route][to] - forward[route][from];
    }

    /** Costo de recorrer de la posición {@code to} a la {@code from}, es decir, al revés. */
    long backwardCost(int route, int from, int to) {
        return backward[route][to] - backward[route][from];
    }

    /** Costo total de todas las rutas. O(número de rutas). */
    long cost() {
        long total = 0;
        for (long[] ahead : forward) {
            total += ahead[ahead.length - 1];
        }
        return total;
    }

    /** Clientes de la ruta, sin el depósito. */
    int[] customers(int route) {
        return Arrays.copyOfRange(nodes[route], 1, nodes[route].length - 1);
    }

    /** Reemplaza la ruta por {@code customers}, que puede quedar vacía. */
    void set(int route, int[] customers) {
        int length = customers.length + 2;
        int[] path = new int[length];
        System.arraycopy(customers, 0, path, 1, customers.length);
        int[] loads = new int[length];
        long[] ahead = new long[length];
        long[] behind = new long[length];
        for (int p = 1; p < length; p++) {
            loads[p] = loads[p - 1] + instance.demand(path[p]);
            ahead[p] = ahead[p - 1] + instance.distance(path[p - 1], path[p]);
            behind[p] = behind[p - 1] + instance.distance(path[p], path[p - 1]);
        }
        for (int p = 1; p < length - 1; p++) {
            routeOf[path[p]] = route;
            position[path[p]] = p;
        }
        nodes[route] = path;
        load[route] = loads;
        forward[route] = ahead;
        backward[route] = behind;
    }

    /** La solución sin las rutas que quedaron vacías. */
    Solution toSolution() {
        return new Solution(Arrays.stream(nodes)
                .filter(path -> path.length > 2)
                .map(path -> Arrays.copyOfRange(path, 1, path.length - 1))
                .toArray(int[][]::new));
    }
}
