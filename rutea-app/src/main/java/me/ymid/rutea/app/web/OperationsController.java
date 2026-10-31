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

import me.ymid.rutea.app.application.CityPlans;
import me.ymid.rutea.app.application.ClosureEvent;
import me.ymid.rutea.app.application.EventsPage;
import me.ymid.rutea.app.application.LiveKey;
import me.ymid.rutea.app.application.LocalityOutline;
import me.ymid.rutea.app.application.OperationEvent;
import me.ymid.rutea.app.application.OperationsService;
import me.ymid.rutea.app.application.OverflowEvent;
import me.ymid.rutea.app.application.SelectionPlan;
import me.ymid.rutea.app.application.SensorsMessage;
import me.ymid.rutea.app.application.TelemetryMessage;
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

    public OperationsController(OperationsService operations, CityPlans cityPlans) {
        this.operations = operations;
        this.cityPlans = cityPlans;
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
        return operations.plan(LiveKey.of(locality, slot));
    }

    @GetMapping("/live/{locality}/{slot}/events")
    public EventsPage events(@PathVariable("locality") int locality, @PathVariable("slot") String slot,
            @RequestParam(name = "after", defaultValue = "0") int after) {
        return operations.events(LiveKey.of(locality, slot), after);
    }

    @PostMapping("/live/{locality}/{slot}/breakdowns")
    public List<OperationEvent> breakdown(@PathVariable("locality") int locality, @PathVariable("slot") String slot,
            @RequestBody BreakdownRequest request) {
        return operations.breakdown(LiveKey.of(locality, slot), request.truck(), request.atSeconds());
    }

    @PostMapping("/live/{locality}/{slot}/overflows")
    public OverflowEvent overflow(@PathVariable("locality") int locality, @PathVariable("slot") String slot,
            @RequestBody OverflowRequest request) {
        return operations.overflow(LiveKey.of(locality, slot), request.container(), request.atSeconds());
    }

    @PostMapping("/live/{locality}/{slot}/closures")
    public ClosureEvent closure(@PathVariable("locality") int locality, @PathVariable("slot") String slot,
            @RequestBody ClosureRequest request) {
        return operations.closure(LiveKey.of(locality, slot), request.lon(), request.lat(), request.atSeconds());
    }

    @GetMapping("/live/{locality}/{slot}/sensors")
    public SensorsMessage sensors(@PathVariable("locality") int locality, @PathVariable("slot") String slot) {
        return operations.sensors(LiveKey.of(locality, slot));
    }

    @GetMapping("/live/{locality}/{slot}/telemetry")
    public TelemetryMessage telemetry(@PathVariable("locality") int locality, @PathVariable("slot") String slot) {
        return operations.telemetry(LiveKey.of(locality, slot));
    }

    @PutMapping("/live/{locality}/{slot}/clock")
    public TelemetryMessage clock(@PathVariable("locality") int locality, @PathVariable("slot") String slot,
            @RequestBody ClockRequest request) {
        return operations.clock(LiveKey.of(locality, slot), request.running(), request.speed());
    }
}
