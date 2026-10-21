package me.ymid.rutea.app.planning;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
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
 * Resuelve el escenario de Suba y devuelve el turno de cada compactador como una geometría sobre las calles
 * reales: base → contenedores → portería del relleno → base, con la hora de paso por cada punto y una parada
 * de {@link SubaFigures#SERVICE_SECONDS_PER_CONTAINER} en cada contenedor.
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

    public static DemoPlan plan(SubaScenario scenario, long seed) {
        // El límite de tiempo es solo un seguro: con 2.000 iteraciones el plan termina mucho antes.
        return plan(scenario, new AlnsSolver(), new SolverSettings(seed, Duration.ofMinutes(10), ITERATIONS));
    }

    static DemoPlan plan(SubaScenario scenario, Solver solver, SolverSettings settings) {
        Instance instance = scenario.instance();
        Solution solution = solver.solve(instance, settings);
        Evaluation evaluation = Evaluator.evaluate(instance, solution);
        if (!evaluation.feasible()) {
            throw new IllegalStateException(instance.name() + " infactible: " + evaluation.violations());
        }

        RoadGraph graph = scenario.graph();
        ShortestPaths paths = new ShortestPaths(graph, ShortestPaths.Weight.METERS);
        List<Container> containers = scenario.containers();
        String[] routeOf = new String[containers.size()];
        List<DemoPlan.Route> routes = new ArrayList<>();
        for (int k = 0; k < solution.routeCount(); k++) {
            String id = String.format("N-%02d", k + 1);
            List<String> stops = new ArrayList<>();
            int[] nodes = new int[solution.routeLength(k) + 2];
            int load = 0;
            for (int p = 0; p < solution.routeLength(k); p++) {
                int customer = solution.customer(k, p);
                nodes[p + 1] = customer;
                routeOf[customer - 1] = id;
                stops.add(containers.get(customer - 1).id());
                load += instance.demand(customer);
            }
            Geometry geometry = new Geometry(graph);
            geometry.start(scenario.depotVertex());
            for (int i = 1; i < nodes.length; i++) {
                // El nodo 0 es la base al salir y la portería al llegar.
                int from = i == 1 ? scenario.depotVertex() : scenario.points()[nodes[i - 1]];
                int to = nodes[i] == 0 ? scenario.gateVertex() : scenario.points()[nodes[i]];
                long meters = follow(paths, geometry, from, to);
                if (meters != instance.distance(nodes[i - 1], nodes[i])) {
                    throw new IllegalStateException(id + ": el camino dibujado mide " + meters
                            + " m y la matriz dice " + instance.distance(nodes[i - 1], nodes[i]));
                }
                if (nodes[i] != 0) {
                    geometry.stop(SubaFigures.SERVICE_SECONDS_PER_CONTAINER);
                }
            }
            long back = follow(paths, geometry, scenario.gateVertex(), scenario.depotVertex());
            if (back != scenario.returnMeters()) {
                throw new IllegalStateException(id + ": el regreso dibujado mide " + back + " m y el escenario dice "
                        + scenario.returnMeters());
            }
            long distance = Evaluator.routeCost(instance, solution, k) + back;
            routes.add(new DemoPlan.Route(id, WasteStream.NON_RECYCLABLE, instance.capacity(), load, distance,
                    geometry.seconds(), stops, geometry.path, geometry.times.stream().mapToInt(Integer::intValue).toArray()));
        }

        List<DemoPlan.Container> planContainers = new ArrayList<>(containers.size());
        for (int i = 0; i < containers.size(); i++) {
            Container c = containers.get(i);
            planContainers.add(new DemoPlan.Container(c.id(), c.stream(), lonLat(c.latE7(), c.lonE7()), c.kg(),
                    routeOf[i]));
        }
        List<DemoPlan.Site> sites = List.of(
                new DemoPlan.Site("relleno", "Relleno Doña Juana · portería",
                        lonLat(graph.latE7(scenario.gateVertex()), graph.lonE7(scenario.gateVertex()))),
                new DemoPlan.Site("base", "Base de Área Limpia · Fontibón",
                        lonLat(graph.latE7(scenario.depotVertex()), graph.lonE7(scenario.depotVertex()))));
        DemoPlan.Meta meta = new DemoPlan.Meta("Suba", true, "Calles reales de OpenStreetMap; contenedores y cargas simulados",
                solver.name(), settings.seed(), settings.iterationLimit(), SubaFigures.SHIFT_START,
                SubaFigures.SERVICE_SECONDS_PER_CONTAINER, "© colaboradores de OpenStreetMap (ODbL 1.0)");
        return new DemoPlan(meta, sites, planContainers, routes);
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
