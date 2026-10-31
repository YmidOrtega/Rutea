package me.ymid.rutea.app.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import me.ymid.rutea.app.persistence.OperationLog;

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
    private final Map<LiveKey, FutureTask<LiveSession>> sessions = new LinkedHashMap<>(16, 0.75f, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<LiveKey, FutureTask<LiveSession>> eldest) {
            return size() > CACHED;
        }
    };

    public OperationsService(BogotaData data, OperationLog log, @Value("${rutea.warm-up:true}") boolean warmUp) {
        this.data = data;
        this.log = log;
        this.warmUp = warmUp;
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
