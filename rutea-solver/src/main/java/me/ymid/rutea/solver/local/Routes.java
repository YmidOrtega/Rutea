package me.ymid.rutea.solver.local;

import java.util.Arrays;

import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;

/**
 * Cada ruta lleva el depósito en ambos extremos y, por posición, la carga y el costo acumulados
 * hacia adelante y al revés. El acumulado al revés permite evaluar en O(1) la inversión de un tramo
 * también con distancias asimétricas.
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

    int position(int customer) {
        return position[customer];
    }

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

    int loadUpTo(int route, int position) {
        return load[route][position];
    }

    int load(int route) {
        return load[route][nodes[route].length - 1];
    }

    long forwardCost(int route, int from, int to) {
        return forward[route][to] - forward[route][from];
    }

    long backwardCost(int route, int from, int to) {
        return backward[route][to] - backward[route][from];
    }

    long cost() {
        long total = 0;
        for (long[] ahead : forward) {
            total += ahead[ahead.length - 1];
        }
        return total;
    }

    int[] customers(int route) {
        return Arrays.copyOfRange(nodes[route], 1, nodes[route].length - 1);
    }

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

    Solution toSolution() {
        return new Solution(Arrays.stream(nodes)
                .filter(path -> path.length > 2)
                .map(path -> Arrays.copyOfRange(path, 1, path.length - 1))
                .toArray(int[][]::new));
    }
}
