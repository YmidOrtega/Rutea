package me.ymid.rutea.app.web;

import java.util.List;
import java.util.OptionalLong;

import org.springframework.web.bind.annotation.GetMapping;
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
    private final EventsHub hub;
    private final Telemetry telemetry;

    public OperationsController(OperationsService operations, EventsHub hub, Telemetry telemetry) {
        this.operations = operations;
        this.hub = hub;
        this.telemetry = telemetry;
    }

    @GetMapping("/plan")
    public DemoPlan plan() {
        return operations.plan();
    }

    @GetMapping("/events")
    public EventsPage events(@RequestParam(name = "after", defaultValue = "0") int after) {
        return operations.events(after);
    }

    /**
     * Replanifica y difunde a todos los clientes la avería y los desbordes que hubo que volver a despachar; quien
     * la pidió también los recibe en la respuesta.
     */
    @PostMapping("/breakdowns")
    public List<OperationEvent> breakdown(@RequestBody BreakdownRequest request) {
        List<OperationEvent> events = operations.breakdown(request.truck(),
                request.atSeconds().orElseGet(operations::now));
        events.forEach(hub::broadcast);
        return events;
    }

    @PostMapping("/overflows")
    public OverflowEvent overflow(@RequestBody OverflowRequest request) {
        OverflowEvent event = operations.overflow(request.container(),
                request.atSeconds().orElseGet(operations::now));
        hub.broadcast(event);
        return event;
    }

    @PostMapping("/closures")
    public ClosureEvent closure(@RequestBody ClosureRequest request) {
        ClosureEvent event = operations.closure(request.lon(), request.lat(),
                request.atSeconds().orElseGet(operations::now));
        hub.broadcast(event);
        return event;
    }

    @GetMapping("/sensors")
    public SensorsMessage sensors() {
        return telemetry.sensors();
    }

    @GetMapping("/telemetry")
    public TelemetryMessage telemetry() {
        return telemetry.snapshot();
    }

    /** Reproducir con el turno terminado empieza uno nuevo: los clientes reciben el turno y borran los eventos. */
    @PutMapping("/clock")
    public TelemetryMessage clock(@RequestBody ClockRequest request) {
        if (operations.clock(request.running(), request.speed())) {
            hub.broadcast(operations.shiftMessage());
        }
        telemetry.publishNow();
        return telemetry.snapshot();
    }
}
