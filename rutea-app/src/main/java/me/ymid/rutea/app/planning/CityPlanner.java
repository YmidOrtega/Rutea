package me.ymid.rutea.app.planning;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.ServiceAreas.ServiceArea;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.solver.Evaluation;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * El cargue lateral de cualquier selección de localidades (ADR 0012 y 0013). Cada ASE con al menos una localidad
 * elegida sale con una instancia por turno: sus contenedores reales en las ASE 1, 2 y 4, y la cantidad oficial simulada
 * en las ASE 3 y 5. Cada instancia se arma y se resuelve en su propio hilo, sin estado compartido; con límite de
 * iteraciones, el plan sale igual en paralelo que en serie.
 */
public final class CityPlanner {

    /**
     * @param scenarioNanos armar el escenario: ubicar los contenedores y calcular la matriz
     * @param solveNanos resolver con ALNS y repartir los viajes entre camiones
     */
    public record AreaPlan(AreaScenario scenario, SideLoading.Shift shift, Solution solution,
            ShiftScheduler.Schedule schedule, long scenarioNanos, long solveNanos) {
    }

    /** Un turno de una ASE elegida sin contenedores en las localidades elegidas: no hay nada que planificar. */
    public record Skipped(ServiceArea area, SideLoading.Shift shift) {
    }

    /** @param nanos de la selección al último plan, en reloj de pared */
    public record Plan(List<AreaPlan> areas, List<Skipped> skipped, long nanos) {

        public Plan {
            areas = List.copyOf(areas);
            skipped = List.copyOf(skipped);
        }
    }

    private CityPlanner() {
    }

    /**
     * @param real contenedores de cada ASE que publica su ubicación, por número de ASE
     * @param parallel un hilo por instancia; si no, una tras otra en este hilo
     */
    public static Plan plan(RoadGraph graph, Map<Integer, List<OperatorContainers.Container>> real,
            Collection<Locality> selection, double fill, long seed, long iterations, boolean parallel) {
        long start = System.nanoTime();
        List<Callable<AreaPlan>> tasks = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Map.Entry<ServiceArea, List<Locality>> entry : ServiceAreas.split(selection).entrySet()) {
            ServiceArea area = entry.getKey();
            List<Locality> chosen = entry.getValue();
            SideLoading.Area sideLoading = SideLoading.of(area);
            for (SideLoading.Shift shift : sideLoading.shifts()) {
                if (sideLoading.planned().isPresent()) {
                    SideLoading.Planned planned = sideLoading.planned().get();
                    tasks.add(() -> solve(shift, seed, iterations, () -> AreaScenario.buildPlanned(graph, area, shift,
                            chosen, planned.count(), planned.liters(), fill, seed)));
                    continue;
                }
                List<OperatorContainers.Container> containers = real.get(area.number());
                if (containers == null) {
                    throw new IllegalArgumentException("falta el extracto de contenedores de " + area.name()
                            + " (mvn -pl rutea-app exec:java@operator-containers)");
                }
                if (AreaScenario.selected(area, shift, chosen, containers).isEmpty()) {
                    skipped.add(new Skipped(area, shift));
                    continue;
                }
                tasks.add(() -> solve(shift, seed, iterations,
                        () -> AreaScenario.buildReal(graph, area, shift, chosen, containers, fill)));
            }
        }
        List<AreaPlan> plans = parallel ? inParallel(tasks) : inSeries(tasks);
        return new Plan(plans, skipped, System.nanoTime() - start);
    }

    private static AreaPlan solve(SideLoading.Shift shift, long seed, long iterations,
            Callable<AreaScenario> build) throws Exception {
        long start = System.nanoTime();
        AreaScenario scenario = build.call();
        long built = System.nanoTime();
        // El límite de tiempo es solo un seguro: con límite de iteraciones el plan termina mucho antes.
        Solution solution = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(seed, Duration.ofMinutes(10), iterations));
        Evaluation evaluation = Evaluator.evaluate(scenario.instance(), solution);
        if (!evaluation.feasible()) {
            throw new IllegalStateException(scenario.instance().name() + " infactible: " + evaluation.violations());
        }
        ShiftScheduler.Schedule schedule = AreaPlanner.fewestTrucks(scenario, solution,
                BogotaFigures.UNLOAD_SECONDS_LOW);
        return new AreaPlan(scenario, shift, solution, schedule, built - start, System.nanoTime() - built);
    }

    private static List<AreaPlan> inSeries(List<Callable<AreaPlan>> tasks) {
        List<AreaPlan> plans = new ArrayList<>(tasks.size());
        for (Callable<AreaPlan> task : tasks) {
            try {
                plans.add(task.call());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return plans;
    }

    private static List<AreaPlan> inParallel(List<Callable<AreaPlan>> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        // Hilos de plataforma: el trabajo es de CPU, no de E/S.
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<AreaPlan> plans = new ArrayList<>(tasks.size());
            for (Future<AreaPlan> future : pool.invokeAll(tasks)) {
                plans.add(future.get());
            }
            return plans;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("planificación interrumpida", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
