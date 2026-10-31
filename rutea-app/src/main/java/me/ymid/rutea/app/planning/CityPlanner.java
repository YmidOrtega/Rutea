package me.ymid.rutea.app.planning;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.ServiceAreas.ServiceArea;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.geo.RoadGraph;
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
     * @param plan el turno de cada camión sobre las calles, con R02, R03, R04 y R07 revisadas y los horarios de paso
     *     por UPZ
     * @param scenarioNanos armar el escenario: ubicar los contenedores y calcular la matriz
     * @param solveNanos resolver con ALNS y repartir los viajes entre camiones
     * @param drawNanos dibujar el plan sobre las calles y revisar la normativa
     */
    public record AreaPlan(AreaScenario scenario, SideLoading.Shift shift, Solution solution,
            ShiftScheduler.Schedule schedule, DemoPlan plan, long scenarioNanos, long solveNanos, long drawNanos) {
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

        /** La flota de cada ASE planificada, con los turnos repartidos con la descarga de 40 min. */
        public List<Fleet> fleets() {
            Map<ServiceArea, List<AreaPlan>> byArea = new LinkedHashMap<>();
            for (AreaPlan plan : areas) {
                byArea.computeIfAbsent(plan.scenario().area(), area -> new ArrayList<>()).add(plan);
            }
            List<Fleet> fleets = new ArrayList<>();
            byArea.forEach((area, plans) -> fleets.add(Fleet.of(area,
                    plans.stream().map(AreaPlan::shift).toList(), plans.stream().map(AreaPlan::schedule).toList())));
            return fleets;
        }
    }

    /**
     * Los camiones de cargue lateral que necesita una ASE en el día. Si sus turnos no se cruzan, un mismo camión
     * trabaja en todos (supuesto: con otra cuadrilla) y la flota es la del turno que más pide; si se cruzan, se suman.
     *
     * @param trucks los del reparto de viajes de cada turno
     * @param lowerBound ningún reparto de esos viajes usa menos; igual a {@code trucks} si el reparto es el mínimo
     * @param declared compactadores de cargue lateral que declara el operador; vacío si no los declara
     */
    public record Fleet(ServiceArea area, int trucks, int lowerBound, boolean sharedAcrossShifts,
            OptionalInt declared) {

        public static Fleet of(ServiceArea area, List<SideLoading.Shift> shifts,
                List<ShiftScheduler.Schedule> schedules) {
            boolean shared = true;
            for (int a = 0; a < shifts.size(); a++) {
                for (int b = a + 1; b < shifts.size(); b++) {
                    shared &= !overlap(shifts.get(a), shifts.get(b));
                }
            }
            IntStream trucks = schedules.stream().mapToInt(schedule -> schedule.trucks().size());
            IntStream bounds = schedules.stream().mapToInt(ShiftScheduler.Schedule::lowerBound);
            return new Fleet(area, shared ? trucks.max().orElse(0) : trucks.sum(),
                    shared ? bounds.max().orElse(0) : bounds.sum(), shared, SideLoading.of(area).sideLoaders());
        }

        public int beyondDeclared() {
            return declared.isPresent() ? Math.max(0, trucks - declared.getAsInt()) : 0;
        }

        public Optional<String> warning() {
            if (beyondDeclared() == 0) {
                return Optional.empty();
            }
            return Optional.of(String.format(Locale.ROOT, "%s: el plan pide %d camiones de cargue lateral y %s declara"
                    + " %d", area.name(), trucks, area.operator(), declared.getAsInt()));
        }

        /** Los dos turnos se cruzan en el reloj de 24 h, también si uno pasa de medianoche. */
        static boolean overlap(SideLoading.Shift a, SideLoading.Shift b) {
            int day = 24 * 3600;
            int startA = seconds(a.start());
            int startB = seconds(b.start());
            int fromAToB = Math.floorMod(startB - startA, day);
            int fromBToA = Math.floorMod(startA - startB, day);
            return fromAToB < a.seconds() || fromBToA < b.seconds();
        }

        private static int seconds(String hhmm) {
            return Integer.parseInt(hhmm.substring(0, 2)) * 3600 + Integer.parseInt(hhmm.substring(3, 5)) * 60;
        }
    }

    private CityPlanner() {
    }

    /**
     * @param real contenedores de cada ASE que publica su ubicación, por número de ASE
     * @param sites sitios sensibles para R02 y R07: los de toda Bogotá sirven para cualquier selección
     * @param parallel un hilo por instancia; si no, una tras otra en este hilo
     */
    public static Plan plan(RoadGraph graph, Map<Integer, List<OperatorContainers.Container>> real,
            List<SensitiveSites.Site> sites, Collection<Locality> selection, double fill, long seed, long iterations, boolean parallel) {
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
                    tasks.add(() -> solve(shift, sites, seed, iterations, () -> AreaScenario.buildPlanned(graph, area, shift,
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
                tasks.add(() -> solve(shift, sites, seed, iterations,
                        () -> AreaScenario.buildReal(graph, area, shift, chosen, containers, fill)));
            }
        }
        List<AreaPlan> plans = parallel ? inParallel(tasks) : inSeries(tasks);
        return new Plan(plans, skipped, System.nanoTime() - start);
    }

    private static AreaPlan solve(SideLoading.Shift shift, List<SensitiveSites.Site> sites, long seed,
            long iterations, Callable<AreaScenario> build) throws Exception {
        long start = System.nanoTime();
        AreaScenario scenario = build.call().withSites(sites);
        long built = System.nanoTime();
        // El límite de tiempo es solo un seguro: con límite de iteraciones el plan termina mucho antes.
        Solution solution = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(seed, Duration.ofMinutes(10), iterations));
        ShiftScheduler.Schedule schedule = AreaPlanner.fewestTrucks(scenario, solution,
                BogotaFigures.UNLOAD_SECONDS_LOW);
        long solved = System.nanoTime();
        DemoPlan plan = checked(scenario, solution, schedule, seed, iterations);
        return new AreaPlan(scenario, shift, solution, schedule, plan, built - start, solved - built,
                System.nanoTime() - solved);
    }

    /** El plan dibujado, con R02, R03 y R07 revisadas parada por parada y los horarios de paso por UPZ (R04). */
    static DemoPlan checked(AreaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule, long seed,
            long iterations) {
        DemoPlan drawn = AreaPlanner.plan(scenario, solution, schedule, "alns", seed, iterations);
        List<DemoPlan.Sector> sectors = Sectors.of(scenario.area()).schedule(drawn.containers());
        List<Compliance.Check> checks = new ArrayList<>(drawn.compliance());
        checks.add(Compliance.scheduleCheck(sectors));
        checks.sort(Comparator.comparing(Compliance.Check::id));
        return new DemoPlan(meta(drawn.meta(), SideLoading.of(scenario.area())), drawn.sites(), drawn.containers(),
                drawn.routes(), checks, sectors);
    }

    private static DemoPlan.Meta meta(DemoPlan.Meta drawn, SideLoading.Area area) {
        boolean simulated = area.planned().isPresent();
        List<String> assumptions = new ArrayList<>();
        if (simulated) {
            assumptions.add("Contenedores simulados con la cantidad oficial: el operador no publica su ubicación");
        }
        assumptions.add(String.format(Locale.ROOT, "Carga por capacidad: litros × %.0f kg/m³ × %.0f %% de llenado al"
                + " recoger, sin dato publicado", BogotaFigures.LOOSE_WASTE_KG_PER_M3, SideLoading.MEAN_FILL * 100));
        assumptions.add("Compactadores de " + SideLoading.SIDE_LOADER_KG / 1000 + " t con hasta "
                + SideLoading.MAX_TRIPS_PER_TRUCK + " viajes por turno");
        if (!area.frequencyPublished()) {
            assumptions.add("Recolección " + area.collectionsPerWeek() + " veces por semana: el operador no publica"
                    + " la de sus contenedores");
        }
        assumptions.add("Velocidades por tipo de vía y parada de " + BogotaFigures.SERVICE_SECONDS_PER_CONTAINER
                + " s por contenedor");
        assumptions.add("Consumo de " + BogotaFigures.LITERS_PER_KM + " L/km para el CO₂");
        return new DemoPlan.Meta(drawn.zone(), simulated, simulated
                ? "Calles reales de OpenStreetMap; contenedores simulados con la cantidad oficial"
                : "Calles reales de OpenStreetMap; contenedores del operador, de su capa pública de ArcGIS",
                drawn.solver(), drawn.seed(), drawn.iterations(), drawn.shiftStart(), drawn.shiftSeconds(),
                drawn.serviceSeconds(), drawn.unloadSeconds(), drawn.litersPerKm(), drawn.kgCo2PerLiter(),
                drawn.attribution(), assumptions);
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
