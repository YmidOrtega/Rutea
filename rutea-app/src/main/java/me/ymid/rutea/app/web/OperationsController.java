package me.ymid.rutea.app.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import me.ymid.rutea.app.planning.DemoPlan;

@RestController
@RequestMapping("/api")
public class OperationsController {

    /** Una avería pedida desde el mapa: el compactador y la hora del reloj, en segundos desde el inicio del turno. */
    public record BreakdownRequest(String truck, long atSeconds) {
    }

    private final OperationsService operations;
    private final EventsHub hub;

    public OperationsController(OperationsService operations, EventsHub hub) {
        this.operations = operations;
        this.hub = hub;
    }

    @GetMapping("/plan")
    public DemoPlan plan() {
        return operations.plan();
    }

    @GetMapping("/events")
    public List<BreakdownEvent> events() {
        return operations.events();
    }

    /** Replanifica y difunde el evento a todos los clientes; quien la pidió también lo recibe en la respuesta. */
    @PostMapping("/breakdowns")
    public BreakdownEvent breakdown(@RequestBody BreakdownRequest request) {
        BreakdownEvent event = operations.breakdown(request.truck(), request.atSeconds());
        hub.broadcast(event);
        return event;
    }

    @ExceptionHandler(OperationsService.DataMissingException.class)
    ResponseEntity<Map<String, String>> missing(OperationsService.DataMissingException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
