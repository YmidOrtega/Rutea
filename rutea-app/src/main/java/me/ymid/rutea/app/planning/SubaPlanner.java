package me.ymid.rutea.app.planning;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.domain.DieselEmissions;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.ShortestPaths;
import me.ymid.rutea.geo.geometry.Haversine;
import me.ymid.rutea.solver.Evaluation;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.Solver;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * Resuelve el escenario de Suba, reparte los viajes entre camiones ({@link ShiftScheduler}) y devuelve el turno
 * de cada camión como una geometría sobre las calles reales: base → viaje → descarga en el relleno → … → base,
 * con la hora de paso por cada punto, una parada en cada contenedor y la descarga en la portería.
 */
public final class SubaPlanner {

    public static final long DEFAULT_SEED = 7;

    /**
     * Con límite de iteraciones el plan depende solo de la semilla, no de la velocidad de la máquina. 2.000
     * iteraciones de ALNS tardan unos 15 s en un i5-14400F; con más, la semilla 7 no mejora.
     */
    public static final long ITERATIONS = 2_000;

    private SubaPlanner() {
    }

    /**
     * El plan de demo: los viajes de ALNS repartidos en el menor número de camiones, con la descarga más corta
     * del rango publicado (40 min), para que se vean los viajes encadenados. Con 90 min hacen falta casi un
     * camión por viaje; {@link CompareBaseline} muestra los dos extremos.
     */
    public static DemoPlan plan(SubaScenario scenario, long seed) {
        // El límite de tiempo es solo un seguro: con 2.000 iteraciones el plan termina mucho antes.
        Solution solution = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(seed, Duration.ofMinutes(10), ITERATIONS));
        return plan(scenario, solution, fewestTrucks(scenario, solution, SubaFigures.UNLOAD_SECONDS_LOW), "alns",
                seed, ITERATIONS);
    }

    static DemoPlan plan(SubaScenario scenario, Solver solver, SolverSettings settings) {
        Solution solution = solver.solve(scenario.instance(), settings);
        return plan(scenario, solution, solver.name(), settings.seed(), settings.iterationLimit());
    }

    /** Un camión por viaje. */
    public static DemoPlan plan(SubaScenario scenario, Solution solution, String method, long seed, long iterations) {
        return plan(scenario, solution, oneTripEach(scenario, solution, SubaFigures.UNLOAD_SECONDS_HIGH), method, seed,
                iterations);
    }

    public static ShiftScheduler.Schedule oneTripEach(SubaScenario scenario, Solution solution, int unloadSeconds) {
        return ShiftScheduler.schedule(scenario, solution, SubaFigures.SERVICE_SECONDS_PER_CONTAINER, unloadSeconds,
                SubaFigures.SHIFT_SECONDS, 1);
    }

    public static ShiftScheduler.Schedule fewestTrucks(SubaScenario scenario, Solution solution, int unloadSeconds) {
        return ShiftScheduler.schedule(scenario, solution, SubaFigures.SERVICE_SECONDS_PER_CONTAINER, unloadSeconds,
                SubaFigures.SHIFT_SECONDS, 3);
    }

    /**
     * Cualquier solución del escenario, venga de un solver o de una heurística fija como el barrido por
     * cuadrantes, se dibuja con la misma geometría, los mismos tiempos y las mismas comprobaciones.
     */
    public static DemoPlan plan(SubaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule,
            String method, long seed, long iterations) {
        Instance instance = scenario.instance();
        Evaluation evaluation = Evaluator.evaluate(instance, solution);
        if (!evaluation.feasible()) {
            throw new IllegalStateException(instance.name() + " infactible: " + evaluation.violations());
        }

        RoadGraph graph = scenario.graph();
        ShortestPaths paths = new ShortestPaths(graph, ShortestPaths.Weight.METERS);
        List<Container> containers = scenario.containers();
        String[] routeOf = new String[containers.size()];
        int[] visitSeconds = new int[containers.size()];
        List<DemoPlan.Route> routes = new ArrayList<>();
        for (int t = 0; t < schedule.trucks().size(); t++) {
            ShiftScheduler.Truck truck = schedule.trucks().get(t);
            String id = String.format("C-%02d", t + 1);
            List<String> stops = new ArrayList<>();
            int[] tripLoads = new int[truck.trips().length];
            Geometry geometry = new Geometry(graph);
            geometry.start(scenario.depotVertex());
            long meters = 0;
            for (int i = 0; i < truck.trips().length; i++) {
                int k = truck.trips()[i];
                int previous = i == 0 ? 0 : scenario.gate();
                for (int p = 0; p < solution.routeLength(k); p++) {
                    int customer = solution.customer(k, truck.reversed()[i] ? solution.routeLength(k) - 1 - p : p);
                    meters += leg(paths, geometry, scenario, previous, customer, id);
                    visitSeconds[customer - 1] = geometry.seconds();
                    geometry.stop(SubaFigures.SERVICE_SECONDS_PER_CONTAINER);
                    routeOf[customer - 1] = id;
                    stops.add(containers.get(customer - 1).id());
                    tripLoads[i] += instance.demand(customer);
                    previous = customer;
                }
                meters += leg(paths, geometry, scenario, previous, scenario.gate(), id);
                geometry.stop(schedule.unloadSeconds());
            }
            meters += leg(paths, geometry, scenario, scenario.gate(), 0, id);
            if (meters != truck.meters() || geometry.seconds() != truck.seconds()) {
                throw new IllegalStateException(id + ": el turno dibujado mide " + meters + " m y " + geometry.seconds()
                        + " s; la asignación dice " + truck.meters() + " m y " + truck.seconds() + " s");
            }
            routes.add(new DemoPlan.Route(id, WasteStream.NON_RECYCLABLE, instance.capacity(),
                    Arrays.stream(tripLoads).sum(), meters, geometry.seconds(), tripLoads, stops, geometry.path,
                    geometry.times.stream().mapToInt(Integer::intValue).toArray()));
        }

        Compliance.Result compliance = Compliance.evaluate(scenario, visitSeconds, SubaFigures.SHIFT_START);
        List<DemoPlan.Container> planContainers = new ArrayList<>(containers.size());
        for (int i = 0; i < containers.size(); i++) {
            Container c = containers.get(i);
            Compliance.Stop stop = compliance.stops().get(i);
            planContainers.add(new DemoPlan.Container(c.id(), c.stream(), lonLat(c.latE7(), c.lonE7()), c.kg(),
                    routeOf[i], visitSeconds[i], stop.noCompaction(), stop.nightNoise(), stop.reasons()));
        }
        List<DemoPlan.Site> sites = List.of(
                new DemoPlan.Site("relleno", "Relleno Doña Juana · portería",
                        lonLat(graph.latE7(scenario.gateVertex()), graph.lonE7(scenario.gateVertex()))),
                new DemoPlan.Site("base", "Base de Área Limpia · Fontibón",
                        lonLat(graph.latE7(scenario.depotVertex()), graph.lonE7(scenario.depotVertex()))));
        DemoPlan.Meta meta = new DemoPlan.Meta("Suba", true, "Calles reales de OpenStreetMap; contenedores y cargas simulados",
                method, seed, iterations, SubaFigures.SHIFT_START, schedule.shiftSeconds(),
                SubaFigures.SERVICE_SECONDS_PER_CONTAINER, schedule.unloadSeconds(),
                SubaFigures.LITERS_PER_KM, Math.round(DieselEmissions.kgCo2PerLiter() * 10_000) / 10_000.0,
                "© colaboradores de OpenStreetMap (ODbL 1.0)");
        return new DemoPlan(meta, sites, planContainers, routes, compliance.checks());
    }

    /**
     * Dibuja un tramo entre dos lugares de {@link SubaScenario#travel} (0 es la base; {@code gate()}, la
     * portería) y comprueba que mida lo mismo que la matriz con la que se optimizó.
     */
    private static long leg(ShortestPaths paths, Geometry geometry, SubaScenario scenario, int from, int to, String id) {
        long meters = follow(paths, geometry, vertex(scenario, from), vertex(scenario, to));
        if (meters != scenario.travel().meters(from, to)) {
            throw new IllegalStateException(id + ": el tramo dibujado mide " + meters + " m y la matriz dice "
                    + scenario.travel().meters(from, to));
        }
        return meters;
    }

    private static int vertex(SubaScenario scenario, int place) {
        if (place == 0) {
            return scenario.depotVertex();
        }
        return place == scenario.gate() ? scenario.gateVertex() : scenario.points()[place];
    }

    private static long follow(ShortestPaths paths, Geometry geometry, int from, int to) {
        paths.distances(from, new int[] {to});
        return geometry.follow(paths.pathEdges(to));
    }

    /** [lon, lat] con 5 decimales: alrededor de un metro, más fino que lo que se ve en el mapa. */
    static double[] lonLat(int latE7, int lonE7) {
        return new double[] {Math.round(lonE7 / 100.0) / 1e5, Math.round(latE7 / 100.0) / 1e5};
    }

    /** Recorrido de un viaje punto por punto, con la hora de paso según las velocidades del grafo. */
    private static final class Geometry {

        private final RoadGraph graph;
        private final List<double[]> path = new ArrayList<>();
        private final List<Integer> times = new ArrayList<>();
        private double clock;

        Geometry(RoadGraph graph) {
            this.graph = graph;
        }

        void start(int vertex) {
            add(graph.latE7(vertex), graph.lonE7(vertex));
        }

        /** Agrega los arcos en orden y devuelve sus metros. */
        long follow(int[] edges) {
            long meters = 0;
            for (int edge : edges) {
                meters += graph.meters(edge);
                int shape = graph.shape(edge);
                boolean reversed = shape < 0;
                int base = reversed ? ~shape : shape;
                int first = graph.firstShapePoint(base);
                int count = graph.endShapePoint(base) - first;
                double length = 0;
                for (int i = 1; i < count; i++) {
                    length += segment(first, count, reversed, i);
                }
                // El tiempo del arco se reparte entre sus tramos según su largo.
                double edgeStart = clock;
                double done = 0;
                for (int i = 1; i < count; i++) {
                    done += segment(first, count, reversed, i);
                    clock = edgeStart + (length == 0 ? 1 : done / length) * graph.seconds(edge);
                    int point = first + (reversed ? count - 1 - i : i);
                    add(graph.shapeLatE7(point), graph.shapeLonE7(point));
                }
                clock = edgeStart + graph.seconds(edge);
            }
            return meters;
        }

        private double segment(int first, int count, boolean reversed, int i) {
            int a = first + (reversed ? count - i : i - 1);
            int b = first + (reversed ? count - 1 - i : i);
            return Haversine.meters(graph.shapeLatE7(a), graph.shapeLonE7(a), graph.shapeLatE7(b), graph.shapeLonE7(b));
        }

        /** El camión se queda quieto: el mismo punto otra vez, con la hora de salida. */
        void stop(int seconds) {
            double[] here = path.getLast();
            clock += seconds;
            path.add(here);
            times.add((int) Math.round(clock));
        }

        private void add(int latE7, int lonE7) {
            path.add(lonLat(latE7, lonE7));
            times.add((int) Math.round(clock));
        }

        int seconds() {
            return (int) Math.round(clock);
        }
    }
}
