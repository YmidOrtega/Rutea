package me.ymid.rutea.solver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Única fuente de verdad del costo y la factibilidad: ningún solver calcula el costo final por su
 * cuenta.
 */
public final class Evaluator {

    private Evaluator() {
    }

    public static Evaluation evaluate(Instance instance, Solution solution) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(solution, "solution");
        int size = instance.size();
        int[] visits = new int[size];
        List<String> violations = new ArrayList<>();
        long cost = 0;

        for (int k = 0; k < solution.routeCount(); k++) {
            int length = solution.routeLength(k);
            if (length == 0) {
                violations.add("Ruta #" + (k + 1) + ": vacía");
                continue;
            }
            int load = 0;
            int previous = 0;
            for (int p = 0; p < length; p++) {
                int customer = solution.customer(k, p);
                if (customer < 1 || customer >= size) {
                    violations.add("Ruta #" + (k + 1) + ": el nodo " + customer
                            + " no es un cliente (válidos: 1.." + (size - 1) + ")");
                    continue;
                }
                cost += instance.distance(previous, customer);
                load += instance.demand(customer);
                visits[customer]++;
                previous = customer;
            }
            cost += instance.distance(previous, 0);
            if (load > instance.capacity()) {
                violations.add("Ruta #" + (k + 1) + ": carga " + load
                        + " supera la capacidad " + instance.capacity());
            }
        }

        for (int customer = 1; customer < size; customer++) {
            if (visits[customer] == 0) {
                violations.add("Cliente " + customer + ": sin visitar");
            } else if (visits[customer] > 1) {
                violations.add("Cliente " + customer + ": visitado " + visits[customer] + " veces");
            }
        }
        return new Evaluation(cost, violations);
    }

    public static long routeCost(Instance instance, Solution solution, int route) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(solution, "solution");
        long cost = 0;
        int previous = 0;
        for (int p = 0; p < solution.routeLength(route); p++) {
            int customer = solution.customer(route, p);
            if (customer < 1 || customer >= instance.size()) {
                throw new IllegalArgumentException("Ruta #" + (route + 1) + ": el nodo " + customer + " no es un cliente");
            }
            cost += instance.distance(previous, customer);
            previous = customer;
        }
        return cost + instance.distance(previous, 0);
    }
}
