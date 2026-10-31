package me.ymid.rutea.app.web;

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
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.ymid.rutea.app.operations.BreakdownReplanner;
import me.ymid.rutea.app.operations.ContainerSensors;
import me.ymid.rutea.app.operations.LiveOperation;
import me.ymid.rutea.app.persistence.Command;
import me.ymid.rutea.app.persistence.OperationLog;
import me.ymid.rutea.app.planning.AreaPlanner;
import me.ymid.rutea.app.planning.DemoPlan;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.DoorToDoor;
import me.ymid.rutea.app.scenario.ServiceAreas.ServiceArea;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.app.scenario.SimulatedContainers;
import me.ymid.rutea.domain.CollectionRules;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.TravelMatrix;
import me.ymid.rutea.sim.FillSensor;
import me.ymid.rutea.sim.SimulationClock;
import me.ymid.rutea.solver.Solution;

/**
 * La operación en vivo de una localidad en una franja: su plan, lo que le pasa (averías, desbordes, cierres), su reloj,
 * sus sensores y la telemetría que transmite. Cada una lleva su propio turno en el registro.
 */
final class LiveSession {

    private static final Logger LOG = LoggerFactory.getLogger(LiveSession.class);

    /** Un clic sobre una calle cae a pocos metros de ella; más lejos es un parque, un humedal o fuera del mapa. */
    static final double MAX_CLOSURE_METERS = 150;

    /**
     * La telemetría sale cada {@link Telemetry#REPORT_MILLIS}: a 3.600× cada reporte cubre 15 min de turno. Más rápido,
     * un reporte se saltaría el turno entero y los sensores despacharían todos sus desbordes de golpe.
     */
    static final double MAX_SPEED = 3_600;

    /** La velocidad con la que el mapa ya reproducía el plan: una hora de turno en un minuto. */
    static final double CLOCK_SPEED = 60;

    /** Iteraciones de ALNS al replanificar en vivo: las mismas que se midieron en el benchmark de averías. */
    static final long ITERATIONS = 300;

    static final int SENSOR_STEP_PERCENT = 5;

    private final LiveKey key;
    private final ServiceArea area;
    private final AreaPlanner.Fleet waste;
    private final DemoPlan plan;
    private final ContainerSensors sensors;
    private final List<double[]> unloadSites;
    private final OperationLog log;
    private final SimulationClock clock = new SimulationClock(CLOCK_SPEED, 1);
    private final TelemetryFeed feed;
    private LiveOperation operation;
    private final List<OperationEvent> events = new ArrayList<>();
    private final Map<String, Vehicle> vehicles = new LinkedHashMap<>();
    private long shift;
    private int commands;
    private boolean replaying;
    private double sensorsScannedUntil = Double.NEGATIVE_INFINITY;
    private Map<String, Integer> lastReadings = Map.of();
    private boolean announced;
    private long persistedShift = -1;
    private double persistedUntil;

    private LiveSession(LiveKey key, AreaScenario scenario, DoorToDoor.Plan doorToDoor, OperationLog log) {
        this.key = key;
        this.area = scenario.area();
        this.log = log;
        long seed = AreaPlanner.DEFAULT_SEED;
        waste = AreaPlanner.fleet(scenario, BogotaFigures.UNLOAD_SECONDS_LOW, seed);
        operation = new LiveOperation(waste.scenario(), waste.solution(), waste.schedule());
        DemoPlan drawn = waste.plan();
        plan = new DemoPlan(meta(drawn.meta(), doorToDoor), drawn.sites(), drawn.containers(), drawn.routes(),
                drawn.compliance(), drawn.sectors());
        unloadSites = plan.sites().stream().filter(site -> site.id().equals("relleno"))
                .map(DemoPlan.Site::position).toList();
        feed = new TelemetryFeed(unloadSites);
        List<SimulatedContainers.Container> containers = scenario.containers();
        int[] plannedVisit = new int[containers.size()];
        int[] liters = new int[containers.size()];
        for (int i = 0; i < containers.size(); i++) {
            plannedVisit[i] = plan.containers().get(i).visitSeconds();
            liters[i] = SideLoading.litersAtCollection(containers.get(i).kg(), SideLoading.MEAN_FILL);
        }
        sensors = ContainerSensors.of(containers, plannedVisit, liters, SideLoading.of(area).collectionsPerWeek(),
                ContainerSensors.Config.typical(seed));
        resetVehicles();
        resume();
    }

    /** @throws IllegalArgumentException si la localidad no recoge nada en esa franja */
    static LiveSession start(LiveKey key, BogotaData.Loaded data, OperationLog log) {
        DoorToDoor.Live live = DoorToDoor.live(data.graph(), key.locality(), key.slot(), data.real());
        return new LiveSession(key, live.scenario().withSites(data.sites()), live.plan(), log);
    }

    private DemoPlan.Meta meta(DemoPlan.Meta drawn, DoorToDoor.Plan doorToDoor) {
        String containers = SideLoading.of(area).planned().isPresent()
                ? "contenedores simulados de la cantidad oficial de " + area.name()
                : "contenedores reales del operador";
        List<String> assumptions = List.of(
                String.format(Locale.ROOT, "Puerta a puerta %d veces por semana, %s: sin fuente publicada",
                        DoorToDoor.COLLECTIONS_PER_WEEK, key.slot().days().label()),
                "Recolección casa por casa a " + DoorToDoor.COLLECTING_KMH + " km/h y compactadores de "
                        + DoorToDoor.COMPACTOR_KG + " kg: sin fuente publicada",
                "Turnos de 06:00 y 18:00: los de las microrrutas domiciliarias del ASE 1",
                String.format(Locale.ROOT, "%.1f t/día de %s por población (DANE), %.1f en contenedores; el resto se"
                        + " reparte a %.3f kg por metro de calle local", doorToDoor.kgPerDay() / 1000,
                        key.locality().name(), doorToDoor.containerKgPerDay() / 1000, doorToDoor.kgPerMeter()),
                "Velocidades por tipo de vía y parada de " + BogotaFigures.SERVICE_SECONDS_PER_CONTAINER
                        + " s por contenedor",
                "Consumo de " + BogotaFigures.LITERS_PER_KM + " L/km para el CO₂");
        return new DemoPlan.Meta(key.locality().name(), true, "Calles reales de OpenStreetMap; " + containers
                + "; carga del puerta a puerta repartida por población", drawn.solver(), drawn.seed(),
                drawn.iterations(), drawn.shiftStart(), drawn.shiftSeconds(), drawn.serviceSeconds(),
                drawn.unloadSeconds(), drawn.litersPerKm(), drawn.kgCo2PerLiter(), drawn.attribution(), assumptions);
    }

    LiveKey key() {
        return key;
    }

    synchronized DemoPlan plan() {
        return plan;
    }

    /**
     * Avería del compactador {@code truckId} ("C-03") a {@code atSeconds} del inicio del turno. Devuelve la avería
     * y, después, los desbordes que ese camión iba a recoger y ahora recoge otro.
     *
     * @throws IllegalArgumentException si el camión no existe o la hora cae fuera de su turno
     */
    synchronized List<OperationEvent> breakdown(String truckId, long atSeconds) {
        int truck = truckIndex(truckId);
        long end = waste.schedule().trucks().get(truck).seconds();
        if (events.stream().anyMatch(event -> event instanceof BreakdownEvent b && b.truck().equals(truckId))) {
            throw new IllegalArgumentException(truckId + " ya está averiado");
        }
        if (atSeconds < 0 || atSeconds >= end) {
            throw new IllegalArgumentException(truckId + " trabaja de 0 a " + end + " s; la avería a " + atSeconds
                    + " s queda fuera de su turno");
        }
        AreaScenario scenario = operation.scenarioAt(atSeconds);
        BreakdownReplanner.Replan replan = BreakdownReplanner.afterBreakdown(scenario, truck, atSeconds,
                operation.pendingPlanned(truck, atSeconds), waste.schedule().unloadSeconds(), ITERATIONS,
                AreaPlanner.DEFAULT_SEED);
        long dispatch = atSeconds + BogotaFigures.RESERVE_DISPATCH_SECONDS;
        List<DemoPlan.Route> reserve = new ArrayList<>();
        if (replan.orphans().length > 0) {
            DemoPlan drawn = AreaPlanner.planReserve(scenario, replan.trips(), replan.reserve(),
                    AreaPlanner.DEFAULT_SEED, ITERATIONS);
            for (int k = 0; k < drawn.routes().size(); k++) {
                reserve.add(shifted(drawn.routes().get(k), "R-" + truckId.substring(2) + "-" + (k + 1), dispatch));
            }
        }
        BreakdownEvent event = new BreakdownEvent(events.size() + 1, area.number(), truckId, atSeconds, dispatch,
                replan.latencyNanos() / 1e6, ids(Arrays.stream(replan.orphans()).boxed().toList()), reserve,
                replan.firstPickupSeconds(), replan.lastPickupSeconds(), replan.meetsRecoveryRule(),
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
     * Reporte de que el contenedor {@code containerId} está desbordado a {@code atSeconds} del inicio del turno.
     *
     * @throws IllegalArgumentException si no es un contenedor de la operación, la hora cae fuera del turno o ya hay
     *     una recogida en camino por un reporte anterior
     */
    synchronized OverflowEvent overflow(String containerId, long atSeconds) {
        int node = containerNode(containerId);
        if (atSeconds < 0 || atSeconds >= operation.shiftSeconds()) {
            throw new IllegalArgumentException("el turno va de 0 a " + operation.shiftSeconds() + " s; el reporte a "
                    + atSeconds + " s queda fuera");
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i) instanceof OverflowEvent last && last.container().equals(containerId)) {
                if (last.pickupSeconds() > atSeconds) {
                    throw new IllegalArgumentException(containerId + " ya está reportado: " + last.truck()
                            + " lo recoge a las " + LocalTime.parse(waste.scenario().operation().shiftStart())
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
    synchronized ClosureEvent closure(double lon, double lat, long atSeconds) {
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
        AreaScenario scenario = waste.scenario();
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
                    visits.put(scenario.stopId(node), seconds));
        }
        TravelMatrix.Repair repair = closure.repair();
        ClosureEvent event = new ClosureEvent(events.size() + 1, area.number(), atSeconds,
                graph.roadClass(graph.edgesOn(shape)[0]).name(), path, closure.latencyNanos() / 1e6,
                repair.rows() + repair.columns(), repair.matrix().size(), repair.changedPairs(),
                ids(closure.isolated()), ids(closure.dropped()), changes, routes, visits,
                CollectionRules.ROUTE_CHANGE_NOTICE.citation());
        add(event);
        logged(new Command.Closure(shape, atSeconds));
        return event;
    }

    synchronized long now() {
        return (long) clock.seconds();
    }

    synchronized SimulationClock.State clock() {
        return clock.state();
    }

    /**
     * Reproduce o pausa el reloj y fija su velocidad. Reproducir con el turno terminado empieza un turno nuevo: se
     * borran los eventos y el plan vuelve al original.
     *
     * @return si empezó un turno nuevo
     */
    synchronized boolean clock(boolean running, double speed) {
        checkSpeed(speed);
        clock.speed(speed);
        if (!running) {
            clock.pause();
            return false;
        }
        if (!clock.finished()) {
            clock.play();
            return false;
        }
        operation = new LiveOperation(waste.scenario(), waste.solution(), waste.schedule());
        events.clear();
        resetVehicles();
        clock.restart();
        startShift();
        return true;
    }

    static void checkSpeed(double speed) {
        if (speed > MAX_SPEED) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "la velocidad va hasta %.0f×; llegó %.0f×", MAX_SPEED, speed));
        }
    }

    /**
     * Un reporte de telemetría: el turno a quien no lo recibió al conectarse, los desbordes que detectaron los
     * sensores, las posiciones nuevas (que también se guardan) y las lecturas que cambiaron.
     */
    synchronized void publish(boolean always, Consumer<Object> out) {
        if (!announced) {
            out.accept(shiftMessage());
            announced = true;
        }
        double now = clock.seconds();
        sensorOverflows(now).forEach(out);
        feed.next(vehicles(), clock.state(), always).ifPresent(message -> {
            out.accept(message);
            persist(message);
        });
        Map<String, Integer> readings = readings(now);
        Map<String, Integer> changed = new HashMap<>();
        readings.forEach((id, reading) -> {
            if (!reading.equals(lastReadings.get(id))) {
                changed.put(id, reading);
            }
        });
        lastReadings = readings;
        if (!changed.isEmpty()) {
            out.accept(new SensorsMessage(now, changed, Map.of()));
        }
    }

    synchronized TelemetryMessage snapshot() {
        return feed.snapshot(vehicles(), clock.state());
    }

    synchronized SensorsMessage sensors() {
        double seconds = clock.seconds();
        Map<String, Integer> liters = new LinkedHashMap<>();
        for (int i = 0; i < sensors.size(); i++) {
            liters.put(waste.scenario().containers().get(i).id(), sensors.liters(i));
        }
        return new SensorsMessage(seconds, readings(seconds), liters);
    }

    /** Cada punto una sola vez: al retomar un turno, lo ya guardado no se repite. */
    private void persist(TelemetryMessage message) {
        if (shift != persistedShift) {
            persistedShift = shift;
            persistedUntil = log.lastShift(key.code()).filter(saved -> saved.id() == shift && saved.lastSeconds() > 0)
                    .map(OperationLog.Shift::lastSeconds).orElse(Double.NEGATIVE_INFINITY);
        }
        List<OperationLog.Position> positions = new ArrayList<>();
        double latest = persistedUntil;
        for (TelemetryMessage.Report report : message.vehicles()) {
            for (int i = 0; i < report.points().size(); i++) {
                double[] point = report.points().get(i);
                if (point[2] > persistedUntil) {
                    boolean last = i == report.points().size() - 1;
                    positions.add(new OperationLog.Position(report.id(), point[2],
                            last ? Optional.of(report.state().name()) : Optional.empty(), point[0], point[1]));
                    latest = Math.max(latest, point[2]);
                }
            }
        }
        log.positions(shift, positions);
        persistedUntil = latest;
    }

    /**
     * Desbordes que los sensores detectaron desde la revisión anterior hasta {@code to}, ya despachados como si alguien
     * los hubiera reportado, en el orden en que ocurrieron. Uno anterior al turno se despacha cuando empieza.
     */
    private List<OperationEvent> sensorOverflows(double to) {
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

    synchronized long shift() {
        return shift;
    }

    private Map<String, Integer> readings(double seconds) {
        double[][] visits = visits();
        Map<String, Integer> readings = new LinkedHashMap<>();
        for (int i = 0; i < sensors.size(); i++) {
            readings.put(waste.scenario().containers().get(i).id(),
                    FillSensor.reading(sensors.fill(i, visits[i], seconds), SENSOR_STEP_PERCENT));
        }
        return readings;
    }

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
            List<String> stops = vehicle.containers();
            for (int k = 0; k < times.size(); k++) {
                Integer i = index.get(stops.get(k));
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
    synchronized EventsPage events(int after) {
        return new EventsPage(shift, List.copyOf(events.subList(Math.min(after, events.size()), events.size())));
    }

    /** El turno en curso y su último {@code seq}, leídos juntos. */
    synchronized ShiftMessage shiftMessage() {
        return new ShiftMessage(shift, area.number(), key.locality().number(), key.slot().code(), events.size());
    }

    private OverflowEvent record(LiveOperation.Dispatch dispatch, String cause) {
        AreaScenario scenario = waste.scenario();
        String container = scenario.stopId(dispatch.node());
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
                operation.plannedArrivals(t).forEach((node, seconds) -> visits.put(scenario.stopId(node), seconds));
            }
            case RESERVE -> {
                Solution trip = Solution.of(new int[] {dispatch.node()});
                ShiftScheduler.Schedule schedule = ShiftScheduler.schedule(scenario, trip, operation.unloadSeconds(),
                        scenario.operation().shiftSeconds(), 1);
                DemoPlan.Route drawn = AreaPlanner.planReserve(
                        operation.scenarioAt(dispatch.atSeconds() + BogotaFigures.RESERVE_DISPATCH_SECONDS), trip,
                        schedule, AreaPlanner.DEFAULT_SEED, 0)
                        .routes().getFirst();
                routes.add(shifted(drawn, truck, dispatch.atSeconds() + BogotaFigures.RESERVE_DISPATCH_SECONDS));
            }
        }
        OverflowEvent event = new OverflowEvent(events.size() + 1, area.number(), container, dispatch.atSeconds(),
                cause, dispatch.pickup(), truck, dispatch.pickupSeconds(), dispatch.extraMeters(),
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
        shift = log.newShift(key.code());
        commands = 0;
        sensorsScannedUntil = Double.NEGATIVE_INFINITY;
    }

    /**
     * Retoma el último turno guardado de esta operación repitiendo sus órdenes: la operación es determinista, así que
     * el estado vuelve idéntico. Si una orden ya no se puede repetir (cambiaron los datos o el código), empieza un
     * turno nuevo.
     */
    private void resume() {
        Optional<OperationLog.Shift> saved = log.lastShift(key.code());
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
            LOG.info("Turno {} de {} retomado: {} órdenes repetidas en {} ms, reloj en {} s", shift, key.code(),
                    commands, (System.nanoTime() - start) / 1_000_000, Math.round(last));
        } catch (RuntimeException e) {
            LOG.warn("No se pudo retomar el turno {} de {}; empieza uno nuevo", saved.get().id(), key.code(), e);
            operation = new LiveOperation(waste.scenario(), waste.solution(), waste.schedule());
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
        return nodes.stream().map(waste.scenario()::stopId).toList();
    }

    private int containerNode(String containerId) {
        List<SimulatedContainers.Container> containers = waste.scenario().containers();
        for (int i = 0; i < containers.size(); i++) {
            if (containers.get(i).id().equals(containerId)) {
                return i + 1;
            }
        }
        if (containerId != null && containerId.startsWith("T-")) {
            throw new IllegalArgumentException(containerId + " es un trozo de calle del puerta a puerta: se desbordan"
                    + " solo los contenedores");
        }
        throw new IllegalArgumentException("no hay contenedor " + containerId + " en " + key.locality().name() + ", "
                + key.slot().label());
    }

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
}
