package me.ymid.rutea.solver.alns;

import java.util.Arrays;

import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;

/** Cada ruta reserva espacio para todos los clientes: insertar y copiar no reservan memoria. */
final class Plan {

    private final Instance instance;
    private final int[][] routes;
    private final int[] lengths;
    private final int[] loads;
    private int count;
    private long cost;

    Plan(Instance instance, Solution solution) {
        this(instance);
        assign(solution);
    }

    private Plan(Instance instance) {
        this.instance = instance;
        int customers = instance.customerCount();
        routes = new int[customers][customers];
        lengths = new int[customers];
        loads = new int[customers];
    }

    void assign(Solution solution) {
        for (int r = 0; r < count; r++) {
            lengths[r] = 0;
            loads[r] = 0;
        }
        cost = 0;
        for (int r = 0; r < solution.routeCount(); r++) {
            for (int p = 0; p < solution.routeLength(r); p++) {
                routes[r][p] = solution.customer(r, p);
                loads[r] += instance.demand(solution.customer(r, p));
            }
            lengths[r] = solution.routeLength(r);
            cost += routeCost(r);
        }
        count = solution.routeCount();
    }

    Plan copy() {
        Plan copy = new Plan(instance);
        copyInto(copy);
        return copy;
    }

    void copyInto(Plan target) {
        for (int r = 0; r < count; r++) {
            System.arraycopy(routes[r], 0, target.routes[r], 0, lengths[r]);
        }
        System.arraycopy(lengths, 0, target.lengths, 0, count);
        System.arraycopy(loads, 0, target.loads, 0, count);
        for (int r = count; r < target.count; r++) {
            target.lengths[r] = 0;
            target.loads[r] = 0;
        }
        target.count = count;
        target.cost = cost;
    }

    long cost() {
        return cost;
    }

    int routeCount() {
        return count;
    }

    int length(int route) {
        return lengths[route];
    }

    int customer(int route, int position) {
        return routes[route][position];
    }

    int load(int route) {
        return loads[route];
    }

    int before(int route, int position) {
        return position == 0 ? 0 : routes[route][position - 1];
    }

    int after(int route, int position) {
        return position == lengths[route] - 1 ? 0 : routes[route][position + 1];
    }

    void remove(boolean[] removed) {
        for (int r = 0; r < count; r++) {
            int[] route = routes[r];
            int kept = 0;
            for (int p = 0; p < lengths[r]; p++) {
                if (!removed[route[p]]) {
                    route[kept++] = route[p];
                } else {
                    loads[r] -= instance.demand(route[p]);
                }
            }
            if (kept != lengths[r]) {
                cost -= routeCost(r);
                lengths[r] = kept;
                cost += routeCost(r);
            }
        }
    }

    long insertionCost(int customer, int route, int position) {
        int previous = position == 0 ? 0 : routes[route][position - 1];
        int next = position == lengths[route] ? 0 : routes[route][position];
        return (long) instance.distance(previous, customer) + instance.distance(customer, next)
                - instance.distance(previous, next);
    }

    boolean fits(int customer, int route) {
        return loads[route] + instance.demand(customer) <= instance.capacity();
    }

    int emptyRoute() {
        for (int r = 0; r < count; r++) {
            if (lengths[r] == 0) {
                return r;
            }
        }
        return count;
    }

    void insert(int customer, int route, int position) {
        cost += insertionCost(customer, route, position);
        int[] path = routes[route];
        System.arraycopy(path, position, path, position + 1, lengths[route] - position);
        path[position] = customer;
        lengths[route]++;
        loads[route] += instance.demand(customer);
        if (route == count) {
            count++;
        }
    }

    Solution toSolution() {
        int[][] nonEmpty = new int[count][];
        int k = 0;
        for (int r = 0; r < count; r++) {
            if (lengths[r] > 0) {
                nonEmpty[k++] = Arrays.copyOf(routes[r], lengths[r]);
            }
        }
        return new Solution(Arrays.copyOf(nonEmpty, k));
    }

    private long routeCost(int route) {
        if (lengths[route] == 0) {
            return 0;
        }
        int[] path = routes[route];
        long total = instance.distance(0, path[0]);
        for (int p = 1; p < lengths[route]; p++) {
            total += instance.distance(path[p - 1], path[p]);
        }
        return total + instance.distance(path[lengths[route] - 1], 0);
    }
}
