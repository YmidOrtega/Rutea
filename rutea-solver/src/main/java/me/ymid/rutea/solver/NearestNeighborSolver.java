package me.ymid.rutea.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Solver de referencia: cada ruta sale del depósito y va siempre al cliente sin visitar más
 * cercano que todavía cabe en el vehículo; cuando ninguno cabe, vuelve y empieza otra ruta.
 *
 * <p>No busca rutas buenas: es la línea base contra la que se miden los demás y prueba el arnés
 * de punta a punta. Es determinista (los empates van al cliente de menor índice) e ignora la
 * semilla y el límite de tiempo. Costo O(n²).
 *
 * <p>Siempre termina: {@link Instance} garantiza que ninguna demanda supera la capacidad, así que
 * cada ruta nueva atiende al menos un cliente.
 */
public final class NearestNeighborSolver implements Solver {

    @Override
    public String name() {
        return "nearest-neighbor";
    }

    @Override
    public Solution solve(Instance instance, SolverSettings settings) {
        int size = instance.size();
        int capacity = instance.capacity();
        boolean[] visited = new boolean[size];
        int[] route = new int[size - 1];
        List<int[]> routes = new ArrayList<>();
        int pending = size - 1;

        while (pending > 0) {
            int length = 0;
            int load = 0;
            int current = 0;
            while (true) {
                int next = -1;
                int nearest = Integer.MAX_VALUE;
                for (int customer = 1; customer < size; customer++) {
                    if (!visited[customer] && load + instance.demand(customer) <= capacity) {
                        int distance = instance.distance(current, customer);
                        if (distance < nearest) {
                            nearest = distance;
                            next = customer;
                        }
                    }
                }
                if (next < 0) {
                    break;
                }
                visited[next] = true;
                route[length++] = next;
                load += instance.demand(next);
                current = next;
                pending--;
            }
            routes.add(Arrays.copyOf(route, length));
        }
        return new Solution(routes.toArray(int[][]::new));
    }
}
