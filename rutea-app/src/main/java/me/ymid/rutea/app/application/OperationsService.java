package me.ymid.rutea.app.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import me.ymid.rutea.app.persistence.OperationLog;
import me.ymid.rutea.app.planning.DemoPlan;

/**
 * Las operaciones en vivo, una por localidad y franja. Cada una se arma la primera vez que se pide, no al arrancar: la
 * app arranca aunque falten los datos y dice qué falta cuando alguien la pide. Se guardan las últimas
 * {@link #CACHED}; una que sale y se vuelve a pedir retoma su turno del registro.
 */
@Service
public class OperationsService {

    private static final Logger LOG = LoggerFactory.getLogger(OperationsService.class);

    /** Cada una pesa unos 10 MB con la matriz de Suba de noche, la franja más grande. */
    static final int CACHED = 4;

    /** La que se arma al arrancar: Suba de noche tiene contenedores y puerta a puerta. */
    static final LiveKey FIRST = LiveKey.of(11, "LMVNoche");

    private final BogotaData data;
    private final OperationLog log;
    private final boolean warmUp;
    private final ApplicationEventPublisher updates;
    private final Map<LiveKey, FutureTask<LiveSession>> sessions = new LinkedHashMap<>(16, 0.75f, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<LiveKey, FutureTask<LiveSession>> eldest) {
            return size() > CACHED;
        }
    };

    public OperationsService(BogotaData data, OperationLog log, @Value("${rutea.warm-up:true}") boolean warmUp,
            ApplicationEventPublisher updates) {
        this.data = data;
        this.log = log;
        this.warmUp = warmUp;
        this.updates = updates;
    }

    public DemoPlan plan(LiveKey key) {
        return session(key).plan();
    }

    public EventsPage events(LiveKey key, int after) {
        if (after < 0) {
            throw new IllegalArgumentException("after es el último seq que ya tiene el cliente: 0 o más, llegó " + after);
        }
        return session(key).events(after);
    }

    /**
     * Replanifica y difunde a quienes miran la operación la avería y los desbordes que hubo que volver a despachar;
     * quien la pidió también los recibe en la respuesta.
     */
    public List<OperationEvent> breakdown(LiveKey key, String truck, OptionalLong atSeconds) {
        LiveSession live = session(key);
        List<OperationEvent> events = live.breakdown(truck, atSeconds.orElseGet(live::now));
        events.forEach(event -> publish(live, event));
        return events;
    }

    public OverflowEvent overflow(LiveKey key, String container, OptionalLong atSeconds) {
        LiveSession live = session(key);
        OverflowEvent event = live.overflow(container, atSeconds.orElseGet(live::now));
        publish(live, event);
        return event;
    }

    public ClosureEvent closure(LiveKey key, double lon, double lat, OptionalLong atSeconds) {
        LiveSession live = session(key);
        ClosureEvent event = live.closure(lon, lat, atSeconds.orElseGet(live::now));
        publish(live, event);
        return event;
    }

    public SensorsMessage sensors(LiveKey key) {
        return session(key).sensors();
    }

    public TelemetryMessage telemetry(LiveKey key) {
        return session(key).snapshot();
    }

    /**
     * Reproducir con el turno terminado empieza uno nuevo: los clientes reciben el turno y borran los eventos. Sin
     * esperar el siguiente reporte, todos ven el reloj nuevo.
     */
    public TelemetryMessage clock(LiveKey key, boolean running, double speed) {
        LiveSession.checkSpeed(speed);
        LiveSession live = session(key);
        if (live.clock(running, speed)) {
            publish(live, live.shiftMessage());
        }
        live.publish(true, message -> publish(live, message));
        return live.snapshot();
    }

    public Optional<ShiftMessage> shift(LiveKey key) {
        return ready(key).map(LiveSession::shiftMessage);
    }

    void publish(LiveSession live, Object message) {
        updates.publishEvent(new LiveUpdate(live.key(), message));
    }

    /** Arma {@link #FIRST} apenas arranca la app, en otro hilo, para que el primer cliente no espere. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!warmUp) {
            return;
        }
        Thread.ofVirtual().name("rutea-live").start(() -> {
            try {
                LOG.info("{} lista: {} rutas", FIRST.code(), session(FIRST).plan().routes().size());
            } catch (BogotaData.DataMissingException e) {
                LOG.warn("Sin operación en vivo hasta que existan los datos: {}", e.getMessage());
            }
        });
    }

    /**
     * La operación de {@code key}; la primera vez tarda lo que tarda planificarla, y dos clientes que la piden a la
     * vez esperan un solo cálculo.
     *
     * @throws BogotaData.DataMissingException si faltan los datos de Bogotá
     */
    LiveSession session(LiveKey key) {
        FutureTask<LiveSession> task;
        boolean mine = false;
        synchronized (this) {
            task = sessions.get(key);
            if (task == null) {
                BogotaData.Loaded loaded = data.get();
                task = new FutureTask<>(() -> {
                    long start = System.nanoTime();
                    LiveSession session = LiveSession.start(key, loaded, log);
                    LOG.info("Operación {} armada en {} ms", key.code(), (System.nanoTime() - start) / 1_000_000);
                    return session;
                });
                sessions.put(key, task);
                mine = true;
            }
        }
        if (mine) {
            task.run();
        }
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("planificación interrumpida", e);
        } catch (ExecutionException | CancellationException e) {
            synchronized (this) {
                sessions.remove(key, task);
            }
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Sin esperar: vacío mientras se arma o si no se ha pedido. */
    synchronized Optional<LiveSession> ready(LiveKey key) {
        FutureTask<LiveSession> task = sessions.get(key);
        return task != null && task.state() == FutureTask.State.SUCCESS ? Optional.of(task.resultNow())
                : Optional.empty();
    }

    synchronized List<LiveSession> ready() {
        List<LiveSession> ready = new ArrayList<>();
        sessions.values().stream().filter(task -> task.state() == FutureTask.State.SUCCESS)
                .forEach(task -> ready.add(task.resultNow()));
        return ready;
    }
}
