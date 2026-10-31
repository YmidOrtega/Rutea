package me.ymid.rutea.app.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import me.ymid.rutea.app.persistence.OperationLog;
import me.ymid.rutea.app.planning.DemoPlan;

@Service
public class Telemetry {

    static final long REPORT_MILLIS = 250;

    static final int SENSOR_STEP_PERCENT = 5;

    private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);

    private final OperationsService operations;
    private final EventsHub hub;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("rutea-telemetry").daemon().factory());
    private TelemetryFeed feed;
    private Map<String, Integer> lastReadings = Map.of();
    private final OperationLog log;
    private long persistedShift = -1;
    private boolean announced;
    private double persistedUntil;

    public Telemetry(OperationsService operations, EventsHub hub, OperationLog log) {
        this.operations = operations;
        this.hub = hub;
        this.log = log;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ticker.scheduleAtFixedRate(() -> {
            try {
                publish(false);
            } catch (RuntimeException e) {
                // Un reporte que falla no puede cancelar los que siguen: scheduleAtFixedRate se detendría.
                LOG.warn("Falló un reporte de telemetría", e);
            }
        }, REPORT_MILLIS, REPORT_MILLIS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    public synchronized TelemetryMessage snapshot() {
        return feed().snapshot(operations.vehicles(), operations.clock());
    }

    public synchronized SensorsMessage sensors() {
        double seconds = operations.clock().seconds();
        return new SensorsMessage(seconds, operations.readings(seconds, SENSOR_STEP_PERCENT), operations.liters());
    }

    /** Sin esperar el siguiente reporte: después de pausar o cambiar la velocidad, todos ven el reloj nuevo. */
    public synchronized void publishNow() {
        publish(true);
    }

    private synchronized void publish(boolean always) {
        if (!operations.ready()) {
            return;
        }
        if (!announced) {
            // Quien se conectó mientras se armaba el plan no recibió el turno: EventsHub solo lo manda si ya está listo.
            hub.broadcast(operations.shiftMessage());
            announced = true;
        }
        double now = operations.clock().seconds();
        operations.sensorOverflows(now).forEach(hub::broadcast);
        feed().next(operations.vehicles(), operations.clock(), always).ifPresent(message -> {
            hub.broadcast(message);
            persist(message);
        });
        Map<String, Integer> readings = operations.readings(now, SENSOR_STEP_PERCENT);
        Map<String, Integer> changed = new HashMap<>();
        readings.forEach((id, reading) -> {
            if (!reading.equals(lastReadings.get(id))) {
                changed.put(id, reading);
            }
        });
        lastReadings = readings;
        if (!changed.isEmpty()) {
            hub.broadcast(new SensorsMessage(now, changed, Map.of()));
        }
    }

    /** Cada punto una sola vez: al retomar un turno, lo ya guardado no se repite. */
    private void persist(TelemetryMessage message) {
        long shift = operations.shift();
        if (shift != persistedShift) {
            persistedShift = shift;
            persistedUntil = log.lastShift().filter(saved -> saved.id() == shift && saved.lastSeconds() > 0)
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

    private TelemetryFeed feed() {
        if (feed == null) {
            feed = new TelemetryFeed(operations.plan().sites().stream()
                    .filter(site -> site.id().equals("relleno") || site.id().equals("eca"))
                    .map(DemoPlan.Site::position).toList());
        }
        return feed;
    }
}
