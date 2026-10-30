package me.ymid.rutea.app.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import me.ymid.rutea.app.planning.AreaPlanner;
import me.ymid.rutea.app.planning.CityPlanner;
import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;

/**
 * El plan de cualquier selección de localidades sobre el grafo de Bogotá. Con semilla e iteraciones fijas el plan de
 * una selección es siempre el mismo, así que se guardan los últimos {@link #CACHED}: pedir otra vez una selección
 * cuesta lo que cuesta mandarla, y dos clientes que piden la misma a la vez esperan un solo cálculo.
 */
@Service
public class CityPlans {

    private static final Logger LOG = LoggerFactory.getLogger(CityPlans.class);

    static final int CACHED = 8;

    private record Data(RoadGraph graph, Map<Integer, List<OperatorContainers.Container>> real,
            List<SensitiveSites.Site> sites) {
    }

    private final Path graph;
    private final Path sites;
    private final Path containers;
    private Data data;
    private final Map<List<Integer>, FutureTask<SelectionPlan>> plans = new LinkedHashMap<>(16, 0.75f, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<List<Integer>, FutureTask<SelectionPlan>> eldest) {
            return size() > CACHED;
        }
    };

    public CityPlans(@Value("${rutea.bogota-graph}") String graph, @Value("${rutea.bogota-sites}") String sites,
            @Value("${rutea.operator-containers}") String containers) {
        this.graph = Path.of(graph);
        this.sites = Path.of(sites);
        this.containers = Path.of(containers);
    }

    /**
     * @throws IllegalArgumentException si no hay localidades o un número no es de una localidad
     * @throws OperationsService.DataMissingException si falta el grafo, los sitios o el extracto de un operador
     */
    public SelectionPlan plan(Collection<Integer> numbers) {
        List<Locality> selection = selection(numbers);
        List<Integer> key = selection.stream().map(Locality::number).toList();
        FutureTask<SelectionPlan> task;
        boolean mine = false;
        synchronized (this) {
            Data loaded = data();
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

    private Data data() {
        if (data != null) {
            return data;
        }
        List<Path> files = new ArrayList<>(List.of(graph, sites));
        OperatorContainers.LAYERS.forEach(layer -> files.add(containers.resolve(layer.file())));
        for (Path file : files) {
            if (!Files.exists(file)) {
                throw new OperationsService.DataMissingException("Falta " + file.toAbsolutePath().normalize()
                        + ". Se genera con: mvn -pl rutea-geo compile exec:java@bogota,"
                        + " mvn -pl rutea-app exec:java@bogota-sensitive-sites y"
                        + " mvn -pl rutea-app exec:java@operator-containers (ver data/osm/README.md y data/ase/README.md)");
            }
        }
        try {
            Map<Integer, List<OperatorContainers.Container>> real = new LinkedHashMap<>();
            for (OperatorContainers.Layer layer : OperatorContainers.LAYERS) {
                real.put(layer.area(), OperatorContainers.read(containers.resolve(layer.file())));
            }
            data = new Data(RoadGraphFile.read(graph).graph(), real, SensitiveSites.read(sites));
            return data;
        } catch (IOException e) {
            throw new OperationsService.DataMissingException("No se pudieron leer los datos de Bogotá: "
                    + e.getMessage());
        }
    }
}
