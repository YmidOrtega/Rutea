package me.ymid.rutea.app.planning;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.ServiceAreas.Operation;
import me.ymid.rutea.app.scenario.ServiceAreas.ServiceArea;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
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
 * Resuelve el escenario de una ASE, reparte los viajes entre camiones ({@link ShiftScheduler}) y devuelve el turno
 * de cada camión como una geometría sobre las calles reales: base → viaje → descarga en el relleno → … → base,
 * con la hora de paso por cada punto, una parada en cada contenedor y la descarga en la portería.
 */
public final class AreaPlanner {

    public static final long DEFAULT_SEED = 7;

    /**
     * Con límite de iteraciones el plan depende solo de la semilla, no de la velocidad de la máquina. 2.000
     * iteraciones de ALNS tardan unos 15 s en un i5-14400F; con más, la semilla 7 no mejora.
     */
    public static final long ITERATIONS = 2_000;

    private AreaPlanner() {
    }

    /**
     * El plan de demo: los viajes de ALNS repartidos en el menor número de camiones, con la descarga más corta
     * del rango publicado (40 min), para que se vean los viajes encadenados. Con 90 min hacen falta casi un
     * camión por viaje; {@link CompareBaseline} muestra los dos extremos.
     */
    public static DemoPlan plan(AreaScenario scenario, long seed) {
        // El límite de tiempo es solo un seguro: con 2.000 iteraciones el plan termina mucho antes.
        Solution solution = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(seed, Duration.ofMinutes(10), ITERATIONS));
        return plan(scenario, solution, fewestTrucks(scenario, solution, BogotaFigures.UNLOAD_SECONDS_LOW), "alns",
                seed, ITERATIONS);
    }

    static DemoPlan plan(AreaScenario scenario, Solver solver, SolverSettings settings) {
        Solution solution = solver.solve(scenario.instance(), settings);
        return plan(scenario, solution, solver.name(), settings.seed(), settings.iterationLimit());
    }

    /** Un camión por viaje. */
    public static DemoPlan plan(AreaScenario scenario, Solution solution, String method, long seed, long iterations) {
        return plan(scenario, solution, oneTripEach(scenario, solution, BogotaFigures.UNLOAD_SECONDS_HIGH), method, seed,
                iterations);
    }

    public static ShiftScheduler.Schedule oneTripEach(AreaScenario scenario, Solution solution, int unloadSeconds) {
        return ShiftScheduler.schedule(scenario, solution, BogotaFigures.SERVICE_SECONDS_PER_CONTAINER, unloadSeconds,
                scenario.operation().shiftSeconds(), 1);
    }

    public static ShiftScheduler.Schedule fewestTrucks(AreaScenario scenario, Solution solution, int unloadSeconds) {
        return ShiftScheduler.schedule(scenario, solution, BogotaFigures.SERVICE_SECONDS_PER_CONTAINER, unloadSeconds,
                scenario.operation().shiftSeconds(), 3);
    }

    /**
     * Cualquier solución del escenario, venga de un solver o de una heurística fija como el barrido por
     * cuadrantes, se dibuja con la misma geometría, los mismos tiempos y las mismas comprobaciones.
     */
    public static DemoPlan plan(AreaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule,
            String method, long seed, long iterations) {
        Evaluation evaluation = Evaluator.evaluate(scenario.instance(), solution);
        if (!evaluation.feasible()) {
            throw new IllegalStateException(scenario.instance().name() + " infactible: " + evaluation.violations());
        }
        return draw(scenario, solution, schedule, method, seed, iterations,
                scenario.stream() == WasteStream.NON_RECYCLABLE ? "C" : "A");
    }

    /**
     * Los camiones de reserva tras una avería: sus viajes cubren solo los contenedores huérfanos, así que no se
     * exige cobertura total, pero sí la capacidad de cada viaje. Sus rutas se llaman R-01, R-02…
     */
    public static DemoPlan planReserve(AreaScenario scenario, Solution trips, ShiftScheduler.Schedule schedule,
            long seed, long iterations) {
        for (int k = 0; k < trips.routeCount(); k++) {
            int load = 0;
            for (int p = 0; p < trips.routeLength(k); p++) {
                load += scenario.instance().demand(trips.customer(k, p));
            }
            if (load > scenario.instance().capacity()) {
                throw new IllegalStateException("viaje de reserva " + (k + 1) + " con " + load + " kg, más que "
                        + scenario.instance().capacity());
            }
        }
        return draw(scenario, trips, schedule, "alns-reserva", seed, iterations, "R");
    }

    private static DemoPlan draw(AreaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule,
            String method, long seed, long iterations, String idPrefix) {
        RoadGraph graph = scenario.graph();
        ShortestPaths paths = new ShortestPaths(graph, ShortestPaths.Weight.METERS);
        List<Container> containers = scenario.containers();
        String[] routeOf = new String[containers.size()];
        int[] visitSeconds = new int[containers.size()];
        List<DemoPlan.Route> routes = new ArrayList<>();
        for (int t = 0; t < schedule.trucks().size(); t++) {
            routes.add(drawTruck(scenario, paths, solution, schedule.trucks().get(t), schedule.unloadSeconds(),
                    String.format("%s-%02d", idPrefix, t + 1), routeOf, visitSeconds));
        }

        ServiceArea area = scenario.area();
        Operation operation = scenario.operation();
        Compliance.Result compliance = Compliance.evaluate(scenario, visitSeconds, operation.shiftStart());
        Sectors sectors = Sectors.of(area);
        List<DemoPlan.Container> planContainers = new ArrayList<>(containers.size());
        for (int i = 0; i < containers.size(); i++) {
            Container c = containers.get(i);
            Compliance.Stop stop = compliance.stops().get(i);
            planContainers.add(new DemoPlan.Container(c.id(), c.stream(), lonLat(c.latE7(), c.lonE7()), c.kg(),
                    routeOf[i], visitSeconds[i], stop.noCompaction(), stop.nightNoise(), stop.reasons(),
                    sectors.locate(c.latE7(), c.lonE7())));
        }
        List<DemoPlan.Site> sites = scenario.stream() == WasteStream.NON_RECYCLABLE
                ? List.of(
                        new DemoPlan.Site("relleno", "Relleno Doña Juana · portería",
                                lonLat(graph.latE7(scenario.gateVertex()), graph.lonE7(scenario.gateVertex()))),
                        new DemoPlan.Site("base", "Base de " + area.operator() + " · " + area.base().locality(),
                                lonLat(graph.latE7(scenario.depotVertex()), graph.lonE7(scenario.depotVertex()))))
                : List.of(new DemoPlan.Site("eca", "ECA supuesta · centro de " + area.name(),
                        lonLat(graph.latE7(scenario.depotVertex()), graph.lonE7(scenario.depotVertex()))));
        DemoPlan.Meta meta = new DemoPlan.Meta(area.name(), true,
                "Calles reales de OpenStreetMap; contenedores y cargas simulados",
                method, seed, iterations, operation.shiftStart(), schedule.shiftSeconds(),
                BogotaFigures.SERVICE_SECONDS_PER_CONTAINER, schedule.unloadSeconds(),
                BogotaFigures.LITERS_PER_KM, Math.round(DieselEmissions.kgCo2PerLiter() * 10_000) / 10_000.0,
                "© colaboradores de OpenStreetMap (ODbL 1.0)", assumptions(area));
        return new DemoPlan(meta, sites, planContainers, routes, compliance.checks(), List.of());
    }

    /** Tras un cierre, un turno pasa por grafos distintos y cada índice de arco es de su grafo. */
    public record Arc(RoadGraph graph, int edge) {

        public int meters() {
            return graph.meters(edge);
        }

        public int seconds() {
            return graph.seconds(edge);
        }
    }

    /** Un tramo por parada, uno a la portería por viaje y el regreso a la base, en ese orden. */
    public static DemoPlan.Route redraw(AreaScenario scenario, List<int[]> trips, List<List<Arc>> legs,
            int unloadSeconds, String id) {
        int expected = 1;
        for (int[] trip : trips) {
            expected += trip.length + 1;
        }
        if (legs.size() != expected) {
            throw new IllegalArgumentException(id + ": " + legs.size() + " tramos para " + expected + " lugares");
        }
        Instance instance = scenario.instance();
        List<String> stops = new ArrayList<>();
        int[] tripLoads = new int[trips.size()];
        Geometry geometry = new Geometry(scenario.graph());
        geometry.start(scenario.depotVertex());
        long meters = 0;
        int leg = 0;
        for (int i = 0; i < trips.size(); i++) {
            for (int customer : trips.get(i)) {
                meters += geometry.follow(legs.get(leg++));
                geometry.stop(BogotaFigures.SERVICE_SECONDS_PER_CONTAINER);
                stops.add(scenario.containers().get(customer - 1).id());
                tripLoads[i] += instance.demand(customer);
            }
            meters += geometry.follow(legs.get(leg++));
            geometry.stop(unloadSeconds);
        }
        meters += geometry.follow(legs.get(leg));
        return new DemoPlan.Route(id, scenario.stream(), instance.capacity(), Arrays.stream(tripLoads).sum(), meters,
                geometry.seconds(), tripLoads, stops, geometry.path,
                geometry.times.stream().mapToInt(Integer::intValue).toArray());
    }

    private static DemoPlan.Route drawTruck(AreaScenario scenario, ShortestPaths paths, Solution solution,
            ShiftScheduler.Truck truck, int unloadSeconds, String id, String[] routeOf, int[] visitSeconds) {
        Instance instance = scenario.instance();
        List<String> stops = new ArrayList<>();
        int[] tripLoads = new int[truck.trips().length];
        Geometry geometry = new Geometry(scenario.graph());
        geometry.start(scenario.depotVertex());
        long meters = 0;
        for (int i = 0; i < truck.trips().length; i++) {
            int k = truck.trips()[i];
            int previous = i == 0 ? 0 : scenario.gate();
            for (int p = 0; p < solution.routeLength(k); p++) {
                int customer = solution.customer(k, truck.reversed()[i] ? solution.routeLength(k) - 1 - p : p);
                meters += leg(paths, geometry, scenario, previous, customer, id);
                visitSeconds[customer - 1] = geometry.seconds();
                geometry.stop(BogotaFigures.SERVICE_SECONDS_PER_CONTAINER);
                routeOf[customer - 1] = id;
                stops.add(scenario.containers().get(customer - 1).id());
                tripLoads[i] += instance.demand(customer);
                previous = customer;
            }
            meters += leg(paths, geometry, scenario, previous, scenario.gate(), id);
            geometry.stop(unloadSeconds);
        }
        meters += leg(paths, geometry, scenario, scenario.gate(), 0, id);
        if (meters != truck.meters() || geometry.seconds() != truck.seconds()) {
            throw new IllegalStateException(id + ": el turno dibujado mide " + meters + " m y " + geometry.seconds()
                    + " s; la asignación dice " + truck.meters() + " m y " + truck.seconds() + " s");
        }
        return new DemoPlan.Route(id, scenario.stream(), instance.capacity(), Arrays.stream(tripLoads).sum(), meters,
                geometry.seconds(), tripLoads, stops, geometry.path,
                geometry.times.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Lo que el plan supone sin dato publicado: el panel lo muestra tal cual. Fuentes en docs/datos.md. */
    static List<String> assumptions(ServiceArea area) {
        return List.of(
                "Contenedores y cargas simulados con cifras oficiales; su ubicación sigue la longitud de calle local",
                "Velocidades por tipo de vía y parada de " + BogotaFigures.SERVICE_SECONDS_PER_CONTAINER
                        + " s por contenedor",
                "Consumo de " + BogotaFigures.LITERS_PER_KM + " L/km para el CO₂",
                "Aprovechables: " + Math.round(BogotaFigures.RECOVERED_SHARE * 100) + " % de aprovechamiento (prensa),"
                        + " ECA en el centro de " + area.name() + ", vehículos de "
                        + BogotaFigures.RECYCLABLE_VEHICLE_KG + " kg y " + BogotaFigures.ECA_UNLOAD_SECONDS / 60
                        + " min de descarga: sin datos públicos");
    }

    /**
     * Las dos corrientes en un solo plan: compactadores del operador hacia Doña Juana y vehículos sin compactación
     * de una organización de recicladores hacia la ECA (R06, R10), cada flota con el menor número de vehículos.
     */
    public static DemoPlan planBoth(AreaScenario waste, AreaScenario recyclables, long seed) {
        return merge(waste.area(), fleet(waste, BogotaFigures.UNLOAD_SECONDS_LOW, seed).plan(),
                fleet(recyclables, BogotaFigures.ECA_UNLOAD_SECONDS, seed).plan());
    }

    /** Una flota planificada, con lo que hace falta para replanificarla: su solución y su asignación de turnos. */
    public record Fleet(AreaScenario scenario, Solution solution, ShiftScheduler.Schedule schedule, DemoPlan plan) {
    }

    public static Fleet fleet(AreaScenario scenario, int unloadSeconds, long seed) {
        Solution solution = new AlnsSolver().solve(scenario.instance(),
                new SolverSettings(seed, Duration.ofMinutes(10), ITERATIONS));
        ShiftScheduler.Schedule schedule = ShiftScheduler.schedule(scenario, solution,
                BogotaFigures.SERVICE_SECONDS_PER_CONTAINER, unloadSeconds, scenario.operation().shiftSeconds(), 3);
        return new Fleet(scenario, solution, schedule, plan(scenario, solution, schedule, "alns", seed, ITERATIONS));
    }

    public static DemoPlan merge(ServiceArea area, DemoPlan waste, DemoPlan recyclables) {
        List<DemoPlan.Site> sites = new ArrayList<>(waste.sites());
        sites.addAll(recyclables.sites());
        List<DemoPlan.Container> containers = new ArrayList<>(waste.containers());
        containers.addAll(recyclables.containers());
        List<DemoPlan.Route> routes = new ArrayList<>(waste.routes());
        routes.addAll(recyclables.routes());
        int nightNoise = 0;
        List<Compliance.Check> checks = new ArrayList<>();
        for (Compliance.Check check : waste.compliance()) {
            if (check.id().equals("R02")) {
                nightNoise = check.count();
            } else {
                checks.add(check);
            }
        }
        for (Compliance.Check check : recyclables.compliance()) {
            if (check.id().equals("R02")) {
                nightNoise += check.count();
            }
        }
        checks.add(Compliance.nightNoiseCheck(nightNoise));
        checks.addAll(Compliance.fleetChecks(waste.routes().size(), recyclables.routes().size()));
        List<DemoPlan.Sector> sectors = Sectors.of(area).schedule(containers);
        checks.add(Compliance.scheduleCheck(sectors));
        checks.sort(Comparator.comparing(Compliance.Check::id));
        return new DemoPlan(waste.meta(), sites, containers, routes, checks, sectors);
    }

    /**
     * Dibuja un tramo entre dos lugares de {@link AreaScenario#travel} (0 es la base; {@code gate()}, la
     * portería) y comprueba que mida lo mismo que la matriz con la que se optimizó.
     */
    private static long leg(ShortestPaths paths, Geometry geometry, AreaScenario scenario, int from, int to, String id) {
        long meters = follow(paths, geometry, vertex(scenario, from), vertex(scenario, to));
        if (meters != scenario.travel().meters(from, to)) {
            throw new IllegalStateException(id + ": el tramo dibujado mide " + meters + " m y la matriz dice "
                    + scenario.travel().meters(from, to));
        }
        return meters;
    }

    private static int vertex(AreaScenario scenario, int place) {
        if (place == 0) {
            return scenario.depotVertex();
        }
        return place == scenario.gate() ? scenario.gateVertex() : scenario.points()[place];
    }

    private static long follow(ShortestPaths paths, Geometry geometry, int from, int to) {
        paths.distances(from, new int[] {to});
        List<Arc> arcs = new ArrayList<>();
        for (int edge : paths.pathEdges(to)) {
            arcs.add(new Arc(geometry.graph, edge));
        }
        return geometry.follow(arcs);
    }

    /** [lon, lat] con 5 decimales: alrededor de un metro, más fino que lo que se ve en el mapa. */
    static double[] lonLat(int latE7, int lonE7) {
        return new double[] {Math.round(lonE7 / 100.0) / 1e5, Math.round(latE7 / 100.0) / 1e5};
    }

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

        long follow(List<Arc> arcs) {
            long meters = 0;
            for (Arc arc : arcs) {
                RoadGraph graph = arc.graph();
                int edge = arc.edge();
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
