package me.ymid.rutea.app.web;

import java.util.List;
import java.util.OptionalLong;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import me.ymid.rutea.app.planning.DemoPlan;

@RestController
@RequestMapping("/api")
public class OperationsController {

    /** Sin {@code atSeconds}, los eventos ocurren a la hora del reloj de la simulación. */
    public record BreakdownRequest(String truck, OptionalLong atSeconds) {
    }

    public record OverflowRequest(String container, OptionalLong atSeconds) {
    }

    /** La calle más cercana al punto donde se hizo clic se cierra en los dos sentidos. */
    public record ClosureRequest(double lon, double lat, OptionalLong atSeconds) {
    }

    public record ClockRequest(boolean running, double speed) {
    }

    private final OperationsService operations;
    private final CityPlans cityPlans;
    private final EventsHub hub;
    private final Telemetry telemetry;

    public OperationsController(OperationsService operations, CityPlans cityPlans, EventsHub hub,
            Telemetry telemetry) {
        this.operations = operations;
        this.cityPlans = cityPlans;
        this.hub = hub;
        this.telemetry = telemetry;
    }

    /** Las 20 localidades con su borde y su ASE: lo que dibuja el selector. No necesita los datos de OSM. */
    @GetMapping("/localities")
    public List<LocalityOutline> localities() {
        return LocalityOutline.bogota();
    }

    /** {@code ?localities=11,10}: un plan por ASE de la selección. La primera vez tarda lo que tarda planificarla. */
    @GetMapping("/areas")
    public SelectionPlan areas(@RequestParam(name = "localities") List<Integer> localities) {
        return cityPlans.plan(localities);
    }

    /** La primera vez que se pide una localidad y franja, tarda lo que tarda planificarla. */
    @GetMapping("/live/{locality}/{slot}/plan")
    public DemoPlan plan(@PathVariable("locality") int locality, @PathVariable("slot") String slot) {
        return live(locality, slot).plan();
    }

    @GetMapping("/live/{locality}/{slot}/events")
    public EventsPage events(@PathVariable("locality") int locality, @PathVariable("slot") String slot,
            @RequestParam(name = "after", defaultValue = "0") int after) {
        if (after < 0) {
            throw new IllegalArgumentException("after es el último seq que ya tiene el cliente: 0 o más, llegó " + after);
        }
        return live(locality, slot).events(after);
    }

    /**
     * Replanifica y difunde a quienes miran la operación la avería y los desbordes que hubo que volver a despachar;
     * quien la pidió también los recibe en la respuesta.
     */
    @PostMapping("/live/{locality}/{slot}/breakdowns")
    public List<OperationEvent> breakdown(@PathVariable("locality") int locality, @PathVariable("slot") String slot,
            @RequestBody BreakdownRequest request) {
        LiveSession live = live(locality, slot);
        List<OperationEvent> events = live.breakdown(request.truck(), request.atSeconds().orElseGet(live::now));
        events.forEach(event -> hub.broadcast(live.key(), event));
        return events;
    }

    @PostMapping("/live/{locality}/{slot}/overflows")
    public OverflowEvent overflow(@PathVariable("locality") int locality, @PathVariable("slot") String slot,
            @RequestBody OverflowRequest request) {
        LiveSession live = live(locality, slot);
        OverflowEvent event = live.overflow(request.container(), request.atSeconds().orElseGet(live::now));
        hub.broadcast(live.key(), event);
        return event;
    }

    @PostMapping("/live/{locality}/{slot}/closures")
    public ClosureEvent closure(@PathVariable("locality") int locality, @PathVariable("slot") String slot,
            @RequestBody ClosureRequest request) {
        LiveSession live = live(locality, slot);
        ClosureEvent event = live.closure(request.lon(), request.lat(), request.atSeconds().orElseGet(live::now));
        hub.broadcast(live.key(), event);
        return event;
    }

    @GetMapping("/live/{locality}/{slot}/sensors")
    public SensorsMessage sensors(@PathVariable("locality") int locality, @PathVariable("slot") String slot) {
        return live(locality, slot).sensors();
    }

    @GetMapping("/live/{locality}/{slot}/telemetry")
    public TelemetryMessage telemetry(@PathVariable("locality") int locality, @PathVariable("slot") String slot) {
        return live(locality, slot).snapshot();
    }

    /** Reproducir con el turno terminado empieza uno nuevo: los clientes reciben el turno y borran los eventos. */
    @PutMapping("/live/{locality}/{slot}/clock")
    public TelemetryMessage clock(@PathVariable("locality") int locality, @PathVariable("slot") String slot,
            @RequestBody ClockRequest request) {
        LiveSession.checkSpeed(request.speed());
        LiveSession live = live(locality, slot);
        if (live.clock(request.running(), request.speed())) {
            hub.broadcast(live.key(), live.shiftMessage());
        }
        telemetry.publishNow(live);
        return live.snapshot();
    }

    /** La localidad y la franja se validan antes de leer los datos. */
    private LiveSession live(int locality, String slot) {
        return operations.session(LiveKey.of(locality, slot));
    }
}
