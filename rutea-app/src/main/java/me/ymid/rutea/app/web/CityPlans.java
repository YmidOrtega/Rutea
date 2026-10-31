package me.ymid.rutea.app.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import me.ymid.rutea.app.planning.AreaPlanner;
import me.ymid.rutea.app.planning.CityPlanner;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.SideLoading;

/**
 * El plan de cualquier selección de localidades sobre el grafo de Bogotá. Con semilla e iteraciones fijas el plan de
 * una selección es siempre el mismo, así que se guardan los últimos {@link #CACHED}: pedir otra vez una selección
 * cuesta lo que cuesta mandarla, y dos clientes que piden la misma a la vez esperan un solo cálculo.
 */
@Service
public class CityPlans {

    private static final Logger LOG = LoggerFactory.getLogger(CityPlans.class);

    static final int CACHED = 8;

    private final BogotaData data;
    private final Map<List<Integer>, FutureTask<SelectionPlan>> plans = new LinkedHashMap<>(16, 0.75f, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<List<Integer>, FutureTask<SelectionPlan>> eldest) {
            return size() > CACHED;
        }
    };

    public CityPlans(BogotaData data) {
        this.data = data;
    }

    /**
     * @throws IllegalArgumentException si no hay localidades o un número no es de una localidad
     * @throws BogotaData.DataMissingException si falta el grafo, los sitios o el extracto de un operador
     */
    public SelectionPlan plan(Collection<Integer> numbers) {
        List<Locality> selection = selection(numbers);
        List<Integer> key = selection.stream().map(Locality::number).toList();
        FutureTask<SelectionPlan> task;
        boolean mine = false;
        synchronized (this) {
            BogotaData.Loaded loaded = data.get();
            task = plans.get(key);
            if (task == null) {
                task = new FutureTask<>(() -> {
                    CityPlanner.Plan plan = CityPlanner.plan(loaded.graph(), loaded.real(), loaded.sites(), selection,
                            SideLoading.MEAN_FILL, AreaPlanner.DEFAULT_SEED, AreaPlanner.ITERATIONS, true);
                    LOG.info("Plan de las localidades {}: {} instancias en {} ms", key, plan.areas().size(),
                            plan.nanos() / 1_000_000);
                    return SelectionPlan.of(selection, plan);
                });
                plans.put(key, task);
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
                plans.remove(key, task);
            }
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private static List<Locality> selection(Collection<Integer> numbers) {
        if (numbers.isEmpty()) {
            throw new IllegalArgumentException("localities lleva al menos una localidad, por su número de 1 a 20");
        }
        List<Locality> selection = new ArrayList<>();
        for (int number : new TreeSet<>(numbers)) {
            selection.add(ServiceAreas.locality(number).orElseThrow(() -> new IllegalArgumentException(
                    "no hay localidad " + number + "; van de 1 (Usaquén) a 20 (Sumapaz)")));
        }
        return selection;
    }
}
