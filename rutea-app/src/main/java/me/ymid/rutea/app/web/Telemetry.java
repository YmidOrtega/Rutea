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

/** Cada {@link #REPORT_MILLIS}, el reporte de cada operación en vivo ya armada, a los clientes que la miran. */
@Service
public class Telemetry {

    static final long REPORT_MILLIS = 250;

    private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);

    private final OperationsService operations;
    private final EventsHub hub;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("rutea-telemetry").daemon().factory());

    public Telemetry(OperationsService operations, EventsHub hub) {
        this.operations = operations;
        this.hub = hub;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ticker.scheduleAtFixedRate(() -> {
            for (LiveSession session : operations.ready()) {
                try {
                    session.publish(false, message -> hub.broadcast(session.key(), message));
                } catch (RuntimeException e) {
                    // Un reporte que falla no puede cancelar los que siguen: scheduleAtFixedRate se detendría.
                    LOG.warn("Falló un reporte de telemetría de {}", session.key().code(), e);
                }
            }
        }, REPORT_MILLIS, REPORT_MILLIS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        ticker.shutdownNow();
    }

    /** Sin esperar el siguiente reporte: después de pausar o cambiar la velocidad, todos ven el reloj nuevo. */
    void publishNow(LiveSession session) {
        session.publish(true, message -> hub.broadcast(session.key(), message));
    }
}
