package me.ymid.rutea.app.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import me.ymid.rutea.app.operations.BreakdownReplanner;
import me.ymid.rutea.app.operations.ContainerSensors;
import me.ymid.rutea.app.persistence.Command;
import me.ymid.rutea.app.persistence.OperationLog;
import me.ymid.rutea.app.operations.LiveOperation;
import me.ymid.rutea.app.planning.DemoPlan;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.planning.SubaPlanner;
import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.app.scenario.SimulatedContainers;
import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.domain.CollectionRules;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.sim.FillSensor;
import me.ymid.rutea.sim.SimulationClock;
import me.ymid.rutea.solver.Solution;

/**
 * El plan vigente de Suba y lo que le pasa en la operación: averías y desbordes. El plan se arma la primera vez que se pide, no al arrancar: así la
 * app arranca aunque falten los datos de OSM (en la CI, por ejemplo) y dice qué falta cuando alguien lo pide.
 */
@Service
public class OperationsService {

    /** Faltan los archivos que salen del extracto de OSM. */
    public static final class DataMissingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        DataMissingException(String message) {
            super(message);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(OperationsService.class);

    /** Un clic sobre una calle cae a pocos metros de ella; más lejos es un parque, un humedal o fuera del mapa. */
    static final double MAX_CLOSURE_METERS = 150;

    /**
     * La telemetría sale cada {@link Telemetry#REPORT_MILLIS}: a 3.600× cada reporte cubre 15 min de turno. Más rápido,
     * un reporte se saltaría el turno entero y los sensores despacharían todos sus desbordes de golpe.
     */
    static final double MAX_SPEED = 3_600;

    private final Path graph;
    private final Path sites;
    private final boolean warmUp;
    private SubaPlanner.Fleet waste;
    private LiveOperation operation;
    private DemoPlan plan;
    private final List<OperationEvent> events = new ArrayList<>();
    private final Map<String, Vehicle> vehicles = new LinkedHashMap<>();
    private SimulationClock clock;
    private ContainerSensors sensors;
    private List<double[]> unloadSites;
    private final OperationLog log;
    private long shift;
    private int commands;
    private boolean replaying;
    private double sensorsScannedUntil = Double.NEGATIVE_INFINITY;

    /**
     * Las rutas llegan como texto: si Spring las convirtiera a {@code Path}, pasaría por el cargador de recursos del
     * servidor, que rechaza rutas con "..".
     */
    public OperationsService(@Value("${rutea.graph}") String graph, @Value("${rutea.sites}") String sites,
            @Value("${rutea.warm-up:true}") boolean warmUp, OperationLog log) {
        this.log = log;
        this.graph = Path.of(graph);
        this.sites = Path.of(sites);
        this.warmUp = warmUp;
    }

    /**
     * Arma el plan apenas arranca la app, en otro hilo: tarda unos 25 s y así el primer cliente no espera. Si faltan
     * los datos no pasa nada aquí; el error se informa cuando alguien pide el plan.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!warmUp) {
            return;
        }
        Thread.ofVirtual().name("rutea-plan").start(() -> {
            try {
                plan();
                LOG.info("Plan de Suba listo: {} rutas", plan.routes().size());
            } catch (DataMissingException e) {
                LOG.warn("Sin plan hasta que existan los datos: {}", e.getMessage());
            }
        });
    }

    public synchronized DemoPlan plan() {
        if (plan == null) {
            build();
        }
        return plan;
    }

    /**
     * Avería del compactador {@code truckId} ("C-03") a {@code atSeconds} del inicio del turno. Devuelve la avería
     * y, después, los desbordes que ese camión iba a recoger y ahora recoge otro.
     *
     * @throws IllegalArgumentException si el camión no existe o la hora cae fuera de su turno
     */
    public synchronized List<OperationEvent> breakdown(String truckId, long atSeconds) {
        plan();
        int truck = truckIndex(truckId);
        long end = waste.schedule().trucks().get(truck).seconds();
        if (events.stream().anyMatch(event -> event instanceof BreakdownEvent b && b.truck().equals(truckId))) {
            throw new IllegalArgumentException(truckId + " ya está averiado");
        }
        if (atSeconds < 0 || atSeconds >= end) {
            throw new IllegalArgumentException(truckId + " trabaja de 0 a " + end + " s; la avería a " + atSeconds
                    + " s queda fuera de su turno");
        }
        SubaScenario scenario = operation.scenarioAt(atSeconds);
        BreakdownReplanner.Replan replan = BreakdownReplanner.afterBreakdown(scenario, truck, atSeconds,
                operation.pendingPlanned(truck, atSeconds), waste.schedule().unloadSeconds(), ITERATIONS,
                SubaPlanner.DEFAULT_SEED);
        long dispatch = atSeconds + SubaFigures.RESERVE_DISPATCH_SECONDS;
        List<DemoPlan.Route> reserve = new ArrayList<>();
        if (replan.orphans().length > 0) {
            DemoPlan drawn = SubaPlanner.planReserve(scenario, replan.trips(), replan.reserve(),
                    SubaPlanner.DEFAULT_SEED, ITERATIONS);
            for (int k = 0; k < drawn.routes().size(); k++) {
                reserve.add(shifted(drawn.routes().get(k), "R-" + truckId.substring(2) + "-" + (k + 1), dispatch));
            }
        }
        List<String> orphans = Arrays.stream(replan.orphans())
                .mapToObj(node -> waste.scenario().containers().get(node - 1).id()).toList();
        BreakdownEvent event = new BreakdownEvent(events.size() + 1, truckId, atSeconds, dispatch, replan.latencyNanos() / 1e6, orphans,
                reserve, replan.firstPickupSeconds(), replan.lastPickupSeconds(), replan.meetsRecoveryRule(),
                CollectionRules.BREAKDOWN_RECOVERY.citation());
        add(event);
        List<OperationEvent> produced = new ArrayList<>(List.of(event));
        for (LiveOperation.Dispatch again : operation.outOfService(truck, atSeconds)) {
            produced.add(record(again, "avería de " + truckId));
        }
        logged(new Command.Breakdown(truckId, atSeconds));
        return produced;
    }

    /**
     * Reporte de que el contenedor {@code containerId} ("N0123") está desbordado a {@code atSeconds} del inicio
     * del turno.
     *
     * @throws IllegalArgumentException si no es un contenedor de no aprovechables, la hora cae fuera del turno o ya
     *     hay una recogida en camino por un reporte anterior
     */
    public synchronized OverflowEvent overflow(String containerId, long atSeconds) {
        plan();
        int node = containerNode(containerId);
        if (atSeconds < 0 || atSeconds >= operation.shiftSeconds()) {
            throw new IllegalArgumentException("el turno va de 0 a " + operation.shiftSeconds() + " s; el reporte a "
                    + atSeconds + " s queda fuera");
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i) instanceof OverflowEvent last && last.container().equals(containerId)) {
                if (last.pickupSeconds() > atSeconds) {
                    throw new IllegalArgumentException(containerId + " ya está reportado: " + last.truck()
                            + " lo recoge a las " + LocalTime.parse(SubaFigures.SHIFT_START)
                                    .plusSeconds(last.pickupSeconds()).format(DateTimeFormatter.ofPattern("HH:mm")));
                }
                break;
            }
        }
        OverflowEvent event = record(operation.overflow(node, atSeconds), "reporte");
        logged(new Command.Overflow(containerId, atSeconds, "reporte"));
        return event;
    }

    /**
     * Cierra la calle más cercana a ({@code lon}, {@code lat}) desde {@code atSeconds}.
     *
     * @throws IllegalArgumentException si el punto queda a más de {@link #MAX_CLOSURE_METERS} de una calle, la calle
     *     ya está cerrada, el cierre aísla la base o la portería, o es anterior al último cierre
     */
    public synchronized ClosureEvent closure(double lon, double lat, long atSeconds) {
        plan();
        if (atSeconds < 0 || atSeconds >= operation.shiftSeconds()) {
            throw new IllegalArgumentException("el turno va de 0 a " + operation.shiftSeconds() + " s; el cierre a "
                    + atSeconds + " s queda fuera");
        }
        RoadGraph graph = waste.scenario().graph();
        int latE7 = (int) Math.round(lat * 1e7);
        int lonE7 = (int) Math.round(lon * 1e7);
        int shape = graph.nearestShape(latE7, lonE7);
        double meters = graph.metersToShape(shape, latE7, lonE7);
        if (meters > MAX_CLOSURE_METERS) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "el punto (%.5f, %.5f) queda a %.0f m de la calle más cercana; un cierre se marca sobre una calle,"
                            + " a menos de %.0f m", lon, lat, meters, MAX_CLOSURE_METERS));
        }
        return close(shape, atSeconds);
    }

    private ClosureEvent close(int shape, long atSeconds) {
        SubaScenario scenario = waste.scenario();
        RoadGraph graph = scenario.graph();
        LiveOperation.Closure closure = operation.close(shape, atSeconds);
        List<double[]> path = new ArrayList<>();
        for (int p = graph.firstShapePoint(shape); p < graph.endShapePoint(shape); p++) {
            path.add(new double[] {graph.shapeLonE7(p) / 1e7, graph.shapeLatE7(p) / 1e7});
        }
        List<ClosureEvent.Change> changes = new ArrayList<>();
        List<DemoPlan.Route> routes = new ArrayList<>();
        Map<String, Long> visits = new LinkedHashMap<>();
        for (LiveOperation.TruckChange change : closure.changes()) {
            String truck = String.format("C-%02d", change.truck() + 1);
            changes.add(new ClosureEvent.Change(truck, change.extraMeters(), change.extraSeconds(), change.rerouted(),
                    change.late()));
            routes.add(operation.route(change.truck(), truck));
            operation.plannedArrivals(change.truck()).forEach((node, seconds) ->
                    visits.put(scenario.containers().get(node - 1).id(), seconds));
        }
        TravelMatrix.Repair repair = closure.repair();
        ClosureEvent event = new ClosureEvent(events.size() + 1, atSeconds,
                graph.roadClass(graph.edgesOn(shape)[0]).name(), path, closure.latencyNanos() / 1e6,
                repair.rows() + repair.columns(), repair.matrix().size(), repair.changedPairs(),
                ids(closure.isolated()), ids(closure.dropped()), changes, routes, visits,
                CollectionRules.ROUTE_CHANGE_NOTICE.citation());
        add(event);
        logged(new Command.Closure(shape, atSeconds));
        return event;
    }

    /** {@code false} mientras el plan no esté armado: la telemetría espera sin forzar el armado. */
    public synchronized boolean ready() {
        return plan != null;
    }

    /** Segundos del reloj de la simulación, desde el inicio del turno. */
    public synchronized long now() {
        plan();
        return (long) clock.seconds();
    }

    public synchronized SimulationClock.State clock() {
        plan();
        return clock.state();
    }

    /**
     * Reproduce o pausa el reloj compartido y fija su velocidad. Reproducir con el turno terminado empieza un turno
     * nuevo: se borran los eventos y el plan vuelve al original.
     *
     * @return si empezó un turno nuevo
     */
    public synchronized boolean clock(boolean running, double speed) {
        if (speed > MAX_SPEED) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "la velocidad va hasta %.0f×; llegó %.0f×", MAX_SPEED, speed));
        }
        plan();
        clock.speed(speed);
        if (!running) {
            clock.pause();
            return false;
        }
        if (!clock.finished()) {
            clock.play();
            return false;
        }
        operation = new LiveOperation(waste.scenario(), waste.solution(), waste.schedule(),
                SubaFigures.SERVICE_SECONDS_PER_CONTAINER);
        events.clear();
        resetVehicles();
        clock.restart();
        startShift();
        return true;
    }

    synchronized List<double[]> unloadSites() {
        return unloadSites;
    }

    /**
     * Desbordes que los sensores detectaron desde la revisión anterior hasta {@code to}, ya despachados como si alguien
     * los hubiera reportado, en el orden en que ocurrieron. Uno anterior al turno se despacha cuando empieza, a las
     * 18:00.
     */
    synchronized List<OperationEvent> sensorOverflows(double to) {
        double from = sensorsScannedUntil;
        if (to <= from) {
            return List.of();
        }
        sensorsScannedUntil = to;
        List<OperationEvent> produced = new ArrayList<>();
        while (true) {
            double[][] visits = visits();
            int next = -1;
            double nextAt = Double.POSITIVE_INFINITY;
            for (int i = 0; i < sensors.size(); i++) {
                OptionalDouble crossing = sensors.fullBetween(i, visits[i], from, to);
                if (crossing.isPresent() && crossing.getAsDouble() < nextAt) {
                    next = i;
                    nextAt = crossing.getAsDouble();
                }
            }
            if (next < 0) {
                return produced;
            }
            sensorOverflow(next, Math.max(0, (long) Math.ceil(nextAt))).ifPresent(produced::add);
            from = nextAt;
        }
    }

    private Optional<OperationEvent> sensorOverflow(int index, long atSeconds) {
        String id = waste.scenario().containers().get(index).id();
        if (pickupPending(id, atSeconds)) {
            return Optional.empty();
        }
        OverflowEvent event = record(operation.overflow(index + 1, atSeconds), "sensor");
        logged(new Command.Overflow(id, atSeconds, "sensor"));
        return Optional.of(event);
    }

    public synchronized long shift() {
        return shift;
    }

    /** Lectura de cada sensor a {@code seconds}, en pasos de {@code stepPercent}. */
    synchronized Map<String, Integer> readings(double seconds, int stepPercent) {
        double[][] visits = visits();
        Map<String, Integer> readings = new LinkedHashMap<>();
        for (int i = 0; i < sensors.size(); i++) {
            readings.put(waste.scenario().containers().get(i).id(),
                    FillSensor.reading(sensors.fill(i, visits[i], seconds), stepPercent));
        }
        return readings;
    }

    synchronized Map<String, Integer> liters() {
        Map<String, Integer> liters = new LinkedHashMap<>();
        for (int i = 0; i < sensors.size(); i++) {
            liters.put(waste.scenario().containers().get(i).id(), sensors.liters(i));
        }
        return liters;
    }

    /** Cuándo llega un vehículo a cada contenedor con sensor, en orden, según los recorridos vigentes. */
    private double[][] visits() {
        Map<String, Integer> index = new HashMap<>();
        List<SimulatedContainers.Container> containers = waste.scenario().containers();
        for (int i = 0; i < containers.size(); i++) {
            index.put(containers.get(i).id(), i);
        }
        List<List<Double>> arrivals = new ArrayList<>();
        containers.forEach(container -> arrivals.add(new ArrayList<>()));
        for (Vehicle vehicle : vehicles.values()) {
            List<Double> times = vehicle.arrivals(unloadSites);
            for (int k = 0; k < times.size(); k++) {
                Integer i = index.get(vehicle.stops().get(k));
                if (i != null) {
                    arrivals.get(i).add(times.get(k));
                }
            }
        }
        double[][] visits = new double[arrivals.size()][];
        for (int i = 0; i < visits.length; i++) {
            visits[i] = arrivals.get(i).stream().mapToDouble(Double::doubleValue).sorted().toArray();
        }
        return visits;
    }

    synchronized List<Vehicle> vehicles() {
        return List.copyOf(vehicles.values());
    }

    /** Ya hay una recogida en camino por un desborde anterior del mismo contenedor. */
    private boolean pickupPending(String containerId, long atSeconds) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i) instanceof OverflowEvent last && last.container().equals(containerId)) {
                return last.pickupSeconds() > atSeconds;
            }
        }
        return false;
    }

    /**
     * Los eventos del turno con {@code seq} mayor a {@code after}, en orden: con 0, un cliente que llega tarde ve el
     * mismo estado que los demás; con su último {@code seq}, uno que se reconecta recibe solo lo que le falta.
     */
    public synchronized EventsPage events(int after) {
        if (after < 0) {
            throw new IllegalArgumentException("after es el último seq que ya tiene el cliente: 0 o más, llegó " + after);
        }
        return new EventsPage(shift, List.copyOf(events.subList(Math.min(after, events.size()), events.size())));
    }

    /** El turno en curso y su último {@code seq}, leídos juntos. */
    public synchronized ShiftMessage shiftMessage() {
        return new ShiftMessage(shift, events.size());
    }

    private OverflowEvent record(LiveOperation.Dispatch dispatch, String cause) {
        SubaScenario scenario = waste.scenario();
        String container = scenario.containers().get(dispatch.node() - 1).id();
        String truck = dispatch.pickup() == LiveOperation.Pickup.RESERVE ? "R-" + container
                : String.format("C-%02d", dispatch.truck() + 1);
        List<DemoPlan.Route> routes = new ArrayList<>();
        Map<String, Long> visits = new LinkedHashMap<>();
        switch (dispatch.pickup()) {
            case PLANNED -> {
            }
            case IN_SERVICE -> {
                int t = dispatch.truck();
                routes.add(operation.route(t, truck));
                operation.plannedArrivals(t).forEach((node, seconds) ->
                        visits.put(scenario.containers().get(node - 1).id(), seconds));
            }
            case RESERVE -> {
                Solution trip = Solution.of(new int[] {dispatch.node()});
                ShiftScheduler.Schedule schedule = ShiftScheduler.schedule(scenario, trip,
                        SubaFigures.SERVICE_SECONDS_PER_CONTAINER, operation.unloadSeconds(), SubaFigures.SHIFT_SECONDS,
                        1);
                DemoPlan.Route drawn = SubaPlanner.planReserve(
                        operation.scenarioAt(dispatch.atSeconds() + SubaFigures.RESERVE_DISPATCH_SECONDS), trip,
                        schedule, SubaPlanner.DEFAULT_SEED, 0)
                        .routes().getFirst();
                routes.add(shifted(drawn, truck, dispatch.atSeconds() + SubaFigures.RESERVE_DISPATCH_SECONDS));
            }
        }
        OverflowEvent event = new OverflowEvent(events.size() + 1, container, dispatch.atSeconds(), cause,
                dispatch.pickup(), truck, dispatch.pickupSeconds(), dispatch.extraMeters(),
                dispatch.latencyNanos() / 1e6, routes, visits);
        add(event);
        return event;
    }

    private void add(OperationEvent event) {
        events.add(event);
        switch (event) {
            case BreakdownEvent breakdown -> {
                vehicles.computeIfPresent(breakdown.truck(), (id, vehicle) -> vehicle.brokenAt(breakdown.atSeconds()));
                breakdown.reserve().forEach(route -> vehicles.put(route.id(), Vehicle.of(route)));
            }
            case OverflowEvent overflow -> overflow.routes().forEach(route -> vehicles.put(route.id(), Vehicle.of(route)));
            case ClosureEvent closure -> closure.routes().forEach(route -> vehicles.put(route.id(),
                    Vehicle.of(route).brokenAt(vehicles.get(route.id()).brokenAtSeconds())));
        }
        clock.end(endOfShift());
    }

    private void resetVehicles() {
        vehicles.clear();
        plan.routes().forEach(route -> vehicles.put(route.id(), Vehicle.of(route)));
        clock.end(endOfShift());
    }

    private double endOfShift() {
        return vehicles.values().stream().mapToDouble(vehicle -> vehicle.track().end()).max().orElseThrow();
    }

    private void startShift() {
        shift = log.newShift();
        commands = 0;
        sensorsScannedUntil = Double.NEGATIVE_INFINITY;
    }

    /**
     * Retoma el último turno guardado repitiendo sus órdenes: la operación es determinista, así que el estado vuelve
     * idéntico. Si una orden ya no se puede repetir (cambiaron los datos o el código), empieza un turno nuevo.
     */
    private void resume() {
        Optional<OperationLog.Shift> saved = log.lastShift();
        if (saved.isEmpty()) {
            startShift();
            return;
        }
        replaying = true;
        long start = System.nanoTime();
        try {
            for (Command command : saved.get().commands()) {
                switch (command) {
                    case Command.Breakdown breakdown -> breakdown(breakdown.truck(), breakdown.atSeconds());
                    case Command.Overflow overflow when overflow.cause().equals("sensor") ->
                            sensorOverflow(containerNode(overflow.container()) - 1, overflow.atSeconds());
                    case Command.Overflow overflow -> overflow(overflow.container(), overflow.atSeconds());
                    case Command.Closure closure -> close(closure.shape(), closure.atSeconds());
                }
            }
            shift = saved.get().id();
            commands = saved.get().commands().size();
            double last = saved.get().lastSeconds();
            clock.seek(last);
            sensorsScannedUntil = last > 0 ? last : Double.NEGATIVE_INFINITY;
            LOG.info("Turno {} retomado: {} órdenes repetidas en {} ms, reloj en {} s", shift, commands,
                    (System.nanoTime() - start) / 1_000_000, Math.round(last));
        } catch (RuntimeException e) {
            LOG.warn("No se pudo retomar el turno {}; empieza uno nuevo", saved.get().id(), e);
            operation = new LiveOperation(waste.scenario(), waste.solution(), waste.schedule(),
                    SubaFigures.SERVICE_SECONDS_PER_CONTAINER);
            events.clear();
            resetVehicles();
            clock.seek(0);
            startShift();
        } finally {
            replaying = false;
        }
    }

    private void logged(Command command) {
        commands++;
        if (!replaying) {
            log.command(shift, commands, command);
        }
    }

    private List<String> ids(List<Integer> nodes) {
        return nodes.stream().map(node -> waste.scenario().containers().get(node - 1).id()).toList();
    }

    private int containerNode(String containerId) {
        List<SimulatedContainers.Container> containers = waste.scenario().containers();
        for (int i = 0; i < containers.size(); i++) {
            if (containers.get(i).id().equals(containerId)) {
                return i + 1;
            }
        }
        if (plan.containers().stream().anyMatch(container -> container.id().equals(containerId))) {
            throw new IllegalArgumentException(containerId + " es de aprovechables: la operación en vivo cubre por"
                    + " ahora los compactadores");
        }
        throw new IllegalArgumentException("no hay contenedor " + containerId);
    }

    /** La velocidad con la que el mapa ya reproducía el plan: una hora de turno en un minuto. */
    static final double CLOCK_SPEED = 60;

    /** Iteraciones de ALNS al replanificar en vivo: las mismas que se midieron en el benchmark de averías. */
    static final long ITERATIONS = 300;

    private int truckIndex(String truckId) {
        if (truckId == null || !truckId.matches("C-\\d{2}")) {
            throw new IllegalArgumentException("se esperaba un compactador como C-03, llegó " + truckId);
        }
        int index = Integer.parseInt(truckId.substring(2)) - 1;
        if (index < 0 || index >= waste.schedule().trucks().size()) {
            throw new IllegalArgumentException("no hay compactador " + truckId + "; son "
                    + waste.schedule().trucks().size());
        }
        return index;
    }

    private static DemoPlan.Route shifted(DemoPlan.Route route, String id, long seconds) {
        int[] times = route.times().clone();
        for (int i = 0; i < times.length; i++) {
            times[i] += (int) seconds;
        }
        return new DemoPlan.Route(id, route.stream(), route.capacityKg(), route.loadKg(), route.distanceMeters(),
                route.durationSeconds(), route.tripLoadsKg(), route.stops(), route.path(), times);
    }

    private void build() {
        for (Path file : List.of(graph, sites)) {
            if (!Files.exists(file)) {
                throw new DataMissingException("Falta " + file.toAbsolutePath().normalize() + ". Se genera con:"
                        + " mvn -pl rutea-geo compile exec:java y mvn -pl rutea-app exec:java@sensitive-sites"
                        + " (ver data/osm/README.md)");
            }
        }
        try {
            long seed = SubaPlanner.DEFAULT_SEED;
            SubaScenario scenario = SubaScenario.build(RoadGraphFile.read(graph).graph(), seed)
                    .withSites(SensitiveSites.read(sites));
            waste = SubaPlanner.fleet(scenario, SubaFigures.UNLOAD_SECONDS_LOW, seed);
            operation = new LiveOperation(waste.scenario(), waste.solution(), waste.schedule(),
                    SubaFigures.SERVICE_SECONDS_PER_CONTAINER);
            SubaScenario recyclables = SubaScenario.buildRecyclable(scenario.graph(), seed).withSites(scenario.sites());
            plan = SubaPlanner.merge(waste.plan(), SubaPlanner.fleet(recyclables, SubaFigures.ECA_UNLOAD_SECONDS, seed).plan());
            clock = new SimulationClock(CLOCK_SPEED, 1);
            resetVehicles();
            unloadSites = plan.sites().stream()
                    .filter(site -> site.id().equals("relleno") || site.id().equals("eca"))
                    .map(DemoPlan.Site::position).toList();
            Map<String, Integer> plannedVisit = new HashMap<>();
            plan.containers().forEach(container -> plannedVisit.put(container.id(), container.visitSeconds()));
            sensors = ContainerSensors.of(scenario.containers(), scenario.containers().stream()
                    .mapToInt(container -> plannedVisit.get(container.id())).toArray(), SubaFigures.CONTAINER_LITERS,
                    ContainerSensors.Config.typical(seed));
            resume();
        } catch (IOException e) {
            throw new DataMissingException("No se pudo leer el grafo o los sitios: " + e.getMessage());
        }
    }
}
