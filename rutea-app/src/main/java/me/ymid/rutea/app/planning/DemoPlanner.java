package me.ymid.rutea.app.planning;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Evaluation;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.NearestNeighborSolver;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.Solver;
import me.ymid.rutea.solver.SolverSettings;

/**
 * Genera el plan de demo: contenedores simulados en las esquinas de una cuadrícula sobre Suba,
 * una instancia CVRP por corriente (las corrientes nunca comparten ruta) y las rutas que
 * devuelve el solver de referencia.
 *
 * <p>Todo es simulado hasta F3: la cuadrícula reemplaza a las calles, la distancia es Manhattan
 * en metros y los pesos y capacidades son supuestos de orden de magnitud, no datos de operación.
 */
public final class DemoPlanner {

    static final double CENTER_LON = -74.083;
    static final double CENTER_LAT = 4.741;
    static final int BLOCK_METERS = 110;
    static final int COLUMNS = 40;
    static final int ROWS = 30;
    static final int CONTAINERS = 240;

    private static final double METERS_PER_DEGREE = 111_320;

    private DemoPlanner() {
    }

    /** Peso de un contenedor lleno, capacidad del vehículo y proporción de contenedores. */
    private record StreamProfile(String prefix, int fullBinKg, int vehicleKg, double share) {
    }

    private static StreamProfile profile(WasteStream stream) {
        return switch (stream) {
            case NON_RECYCLABLE -> new StreamProfile("NA", 250, 8_000, 0.5);
            case RECYCLABLE -> new StreamProfile("AP", 90, 1_500, 0.3);
            case ORGANIC -> new StreamProfile("OR", 300, 4_000, 0.2);
        };
    }

    private record GridPoint(int x, int y) {

        int manhattan(GridPoint other) {
            return (Math.abs(x - other.x) + Math.abs(y - other.y)) * BLOCK_METERS;
        }
    }

    public static DemoPlan plan(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        GridPoint depot = new GridPoint(0, ROWS / 2);

        // Esquinas distintas al azar (Fisher-Yates parcial), sin el depósito
        int corners = (COLUMNS + 1) * (ROWS + 1);
        int[] order = new int[corners];
        for (int i = 0; i < corners; i++) {
            order[i] = i;
        }
        List<GridPoint> points = new ArrayList<>(CONTAINERS);
        for (int i = 0; points.size() < CONTAINERS; i++) {
            int j = i + random.nextInt(corners - i);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
            GridPoint point = new GridPoint(order[i] % (COLUMNS + 1), order[i] / (COLUMNS + 1));
            if (!point.equals(depot)) {
                points.add(point);
            }
        }

        WasteStream[] streams = new WasteStream[CONTAINERS];
        double[] fills = new double[CONTAINERS];
        int[] kgs = new int[CONTAINERS];
        String[] routeOf = new String[CONTAINERS];
        for (int c = 0; c < CONTAINERS; c++) {
            streams[c] = pickStream(random.nextDouble());
            fills[c] = Math.round((0.2 + 0.8 * random.nextDouble()) * 100) / 100.0;
            kgs[c] = (int) Math.round(fills[c] * profile(streams[c]).fullBinKg());
        }

        Solver solver = new NearestNeighborSolver();
        SolverSettings settings = new SolverSettings(seed, Duration.ofSeconds(1));
        List<DemoPlan.Route> routes = new ArrayList<>();
        for (WasteStream stream : WasteStream.values()) {
            StreamProfile profile = profile(stream);
            List<Integer> members = new ArrayList<>();
            for (int c = 0; c < CONTAINERS; c++) {
                if (streams[c] == stream) {
                    members.add(c);
                }
            }
            if (members.isEmpty()) {
                continue;
            }

            // Nodo 0: depósito; nodo i: el contenedor members.get(i - 1)
            int size = members.size() + 1;
            GridPoint[] nodes = new GridPoint[size];
            int[] demands = new int[size];
            nodes[0] = depot;
            for (int i = 1; i < size; i++) {
                nodes[i] = points.get(members.get(i - 1));
                demands[i] = kgs[members.get(i - 1)];
            }
            int[][] distances = new int[size][size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    distances[i][j] = nodes[i].manhattan(nodes[j]);
                }
            }
            Instance instance = new Instance("suba-demo-" + profile.prefix(), profile.vehicleKg(), demands,
                    DistanceMatrix.of(distances));

            Solution solution = solver.solve(instance, settings);
            Evaluation evaluation = Evaluator.evaluate(instance, solution);
            if (!evaluation.feasible()) {
                throw new IllegalStateException(instance.name() + " infactible: " + evaluation.violations());
            }

            for (int k = 0; k < solution.routeCount(); k++) {
                String id = profile.prefix() + "-" + (k + 1);
                List<String> stops = new ArrayList<>();
                List<GridPoint> visits = new ArrayList<>();
                visits.add(depot);
                int load = 0;
                for (int p = 0; p < solution.routeLength(k); p++) {
                    int container = members.get(solution.customer(k, p) - 1);
                    routeOf[container] = id;
                    stops.add(containerId(container));
                    visits.add(points.get(container));
                    load += kgs[container];
                }
                visits.add(depot);
                routes.add(new DemoPlan.Route(id, stream, profile.vehicleKg(), load,
                        Evaluator.routeCost(instance, solution, k), stops, gridPath(visits)));
            }
        }

        List<DemoPlan.Container> containers = new ArrayList<>(CONTAINERS);
        for (int c = 0; c < CONTAINERS; c++) {
            containers.add(new DemoPlan.Container(containerId(c), streams[c], toLonLat(points.get(c)),
                    fills[c], kgs[c], routeOf[c]));
        }
        DemoPlan.Meta meta = new DemoPlan.Meta("Suba", true, "Cuadrícula simulada, no calles reales",
                BLOCK_METERS, solver.name(), seed);
        return new DemoPlan(meta, toLonLat(depot), containers, routes);
    }

    private static WasteStream pickStream(double draw) {
        double cumulative = 0;
        for (WasteStream stream : WasteStream.values()) {
            cumulative += profile(stream).share();
            if (draw < cumulative) {
                return stream;
            }
        }
        return WasteStream.NON_RECYCLABLE;
    }

    private static String containerId(int container) {
        return String.format("C%03d", container + 1);
    }

    /** Recorrido por las calles de la cuadrícula: entre dos paradas, primero en x y luego en y. */
    private static List<double[]> gridPath(List<GridPoint> visits) {
        List<double[]> path = new ArrayList<>();
        path.add(toLonLat(visits.get(0)));
        for (int i = 1; i < visits.size(); i++) {
            GridPoint from = visits.get(i - 1);
            GridPoint to = visits.get(i);
            if (from.x() != to.x() && from.y() != to.y()) {
                path.add(toLonLat(new GridPoint(to.x(), from.y())));
            }
            if (!from.equals(to)) {
                path.add(toLonLat(to));
            }
        }
        return path;
    }

    /** Esquina de la cuadrícula a {@code [lon, lat]}, con 6 decimales (unos 11 cm). */
    private static double[] toLonLat(GridPoint point) {
        double metersEast = (point.x() - COLUMNS / 2.0) * BLOCK_METERS;
        double metersNorth = (point.y() - ROWS / 2.0) * BLOCK_METERS;
        double lon = CENTER_LON + metersEast / (METERS_PER_DEGREE * Math.cos(Math.toRadians(CENTER_LAT)));
        double lat = CENTER_LAT + metersNorth / METERS_PER_DEGREE;
        return new double[] {round6(lon), round6(lat)};
    }

    private static double round6(double value) {
        return Math.round(value * 1e6) / 1e6;
    }
}
