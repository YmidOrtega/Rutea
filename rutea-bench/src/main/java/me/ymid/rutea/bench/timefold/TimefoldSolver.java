package me.ymid.rutea.bench.timefold;

import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.Solver;
import me.ymid.rutea.solver.SolverSettings;

/**
 * Timefold con su configuración por defecto (heurística de construcción y búsqueda local), como
 * punto de comparación externo. Se detiene solo por tiempo: no tiene límite de iteraciones
 * equivalente, así que con tiempo su resultado depende de la máquina.
 */
public final class TimefoldSolver implements Solver {

    @Override
    public String name() {
        return "timefold";
    }

    @Override
    public Solution solve(Instance instance, SolverSettings settings) {
        List<Visit> visits = new ArrayList<>();
        long demand = 0;
        for (int node = 1; node < instance.size(); node++) {
            visits.add(new Visit(node, instance.demand(node)));
            demand += instance.demand(node);
        }
        // Holgura sobre la cota inferior de camiones: los vacíos no cuestan y uno de menos deja la
        // solución infactible
        int minimum = (int) Math.ceil((double) demand / instance.capacity());
        List<Vehicle> vehicles = new ArrayList<>();
        for (int id = 0; id < minimum + Math.max(2, minimum / 5); id++) {
            vehicles.add(new Vehicle(id, instance));
        }

        SolverConfig config = new SolverConfig()
                .withSolutionClass(Routing.class)
                .withEntityClasses(Vehicle.class)
                .withConstraintProviderClass(RoutingConstraints.class)
                .withRandomSeed(settings.seed())
                .withTerminationSpentLimit(settings.timeLimit());
        Routing solved = SolverFactory.<Routing>create(config).buildSolver().solve(new Routing(visits, vehicles));

        return new Solution(solved.vehicles.stream()
                .filter(vehicle -> !vehicle.visits.isEmpty())
                .map(vehicle -> vehicle.visits.stream().mapToInt(Visit::node).toArray())
                .toArray(int[][]::new));
    }
}
