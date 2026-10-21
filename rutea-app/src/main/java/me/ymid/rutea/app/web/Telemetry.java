package me.ymid.rutea.app.web;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import me.ymid.rutea.app.planning.DemoPlan;

/** Transmite por WebSocket la posición de cada vehículo mientras corre el reloj de la simulación (R08). */
@Service
public class Telemetry {

    static final long REPORT_MILLIS = 250;

    private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);

    private final OperationsService operations;
    private final EventsHub hub;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("rutea-telemetry").daemon().factory());
    private TelemetryFeed feed;

    public Telemetry(OperationsService operations, EventsHub hub) {
        this.operations = operations;
        this.hub = hub;
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

    /** Sin esperar el siguiente reporte: después de pausar o cambiar la velocidad, todos ven el reloj nuevo. */
    public synchronized void publishNow() {
        publish(true);
    }

    private synchronized void publish(boolean always) {
        if (operations.ready()) {
            feed().next(operations.vehicles(), operations.clock(), always).ifPresent(hub::broadcast);
        }
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
