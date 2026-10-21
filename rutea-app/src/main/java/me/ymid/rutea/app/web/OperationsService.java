package me.ymid.rutea.app.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import me.ymid.rutea.app.operations.BreakdownReplanner;
import me.ymid.rutea.app.operations.LiveOperation;
import me.ymid.rutea.app.planning.DemoPlan;
import me.ymid.rutea.app.planning.ShiftScheduler;
import me.ymid.rutea.app.planning.SubaPlanner;
import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.app.scenario.SimulatedContainers;
import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.domain.CollectionRules;
import me.ymid.rutea.geo.RoadGraphFile;
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

    private final Path graph;
    private final Path sites;
    private final boolean warmUp;
    private SubaPlanner.Fleet waste;
    private LiveOperation operation;
    private DemoPlan plan;
    private final List<OperationEvent> events = new ArrayList<>();

    /**
     * Las rutas llegan como texto: si Spring las convirtiera a {@code Path}, pasaría por el cargador de recursos del
     * servidor, que rechaza rutas con "..".
     */
    public OperationsService(@Value("${rutea.graph}") String graph, @Value("${rutea.sites}") String sites,
            @Value("${rutea.warm-up:true}") boolean warmUp) {
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
        BreakdownReplanner.Replan replan = BreakdownReplanner.afterBreakdown(waste.scenario(), waste.solution(),
                waste.schedule(), truck, atSeconds, ITERATIONS, SubaPlanner.DEFAULT_SEED);
        long dispatch = atSeconds + SubaFigures.RESERVE_DISPATCH_SECONDS;
        List<DemoPlan.Route> reserve = new ArrayList<>();
        if (replan.orphans().length > 0) {
            DemoPlan drawn = SubaPlanner.planReserve(waste.scenario(), replan.trips(), replan.reserve(),
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
        events.add(event);
        List<OperationEvent> produced = new ArrayList<>(List.of(event));
        for (LiveOperation.Dispatch again : operation.outOfService(truck, atSeconds)) {
            produced.add(record(again, "avería de " + truckId));
        }
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
        return record(operation.overflow(node, atSeconds), "reporte");
    }

    /** Lo que pasó en esta sesión, en orden: un cliente que llega tarde ve el mismo estado que los demás. */
    public synchronized List<OperationEvent> events() {
        return List.copyOf(events);
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
                routes.add(SubaPlanner.redraw(scenario, operation.trips(t), operation.meters(t),
                        operation.endSeconds(t), operation.unloadSeconds(), truck));
                operation.plannedArrivals(t).forEach((node, seconds) ->
                        visits.put(scenario.containers().get(node - 1).id(), seconds));
            }
            case RESERVE -> {
                Solution trip = Solution.of(new int[] {dispatch.node()});
                ShiftScheduler.Schedule schedule = ShiftScheduler.schedule(scenario, trip,
                        SubaFigures.SERVICE_SECONDS_PER_CONTAINER, operation.unloadSeconds(), SubaFigures.SHIFT_SECONDS,
                        1);
                DemoPlan.Route drawn = SubaPlanner.planReserve(scenario, trip, schedule, SubaPlanner.DEFAULT_SEED, 0)
                        .routes().getFirst();
                routes.add(shifted(drawn, truck, dispatch.atSeconds() + SubaFigures.RESERVE_DISPATCH_SECONDS));
            }
        }
        OverflowEvent event = new OverflowEvent(events.size() + 1, container, dispatch.atSeconds(), cause,
                dispatch.pickup(), truck, dispatch.pickupSeconds(), dispatch.extraMeters(),
                dispatch.latencyNanos() / 1e6, routes, visits);
        events.add(event);
        return event;
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
        } catch (IOException e) {
            throw new DataMissingException("No se pudo leer el grafo o los sitios: " + e.getMessage());
        }
    }
}
