package me.ymid.rutea.app.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;

import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.ShortestPaths;
import me.ymid.rutea.solver.DistanceMatrix;
import me.ymid.rutea.solver.Instance;

/**
 * La recolección de no aprovechables de una localidad: sus contenedores y el puerta a puerta por sus calles locales
 * (ADR 0014), partida en los grupos de días en que pasa el camión. Cada UPZ, el sector en que se publican los horarios
 * (R04), es de un solo grupo, así que el calendario que sale se publica tal cual. Cada grupo es una instancia CVRP.
 */
public final class DoorToDoor {

    /** {@code PENDIENTE_VERIFICAR}: veces por semana que el puerta a puerta pasa por cada calle. */
    public static final int COLLECTIONS_PER_WEEK = 3;

    /** Longitud máxima de un trozo de calle, medida en Suba (ADR 0014). */
    public static final int MAX_CHUNK_METERS = 500;

    /**
     * {@code PENDIENTE_VERIFICAR}: kilos que carga un compactador de carga trasera. Ningún operador los publica; se
     * toman los del cargue lateral, y la flota de la instancia es una sola.
     */
    public static final int COMPACTOR_KG = SideLoading.SIDE_LOADER_KG;

    /** Con {@link #COLLECTIONS_PER_WEEK} pasadas en seis días de servicio, cada calle es de uno de dos grupos. */
    public enum Days {
        MON_WED_FRI("LMV", "lunes, miércoles y viernes"),
        TUE_THU_SAT("MJS", "martes, jueves y sábado");

        private final String code;
        private final String label;

        Days(String code, String label) {
            this.code = code;
            this.label = label;
        }

        public String code() {
            return code;
        }

        public String label() {
            return label;
        }
    }

    /** El sector de un punto: con datos reales, {@code Sectors.of(area)::locate}. */
    @FunctionalInterface
    public interface SectorOf {
        String at(int latE7, int lonE7);
    }

    /**
     * Un grupo de días: nodo 0 es la base y la portería, después van los contenedores y después los trozos.
     *
     * @param matrixNanos calcular la matriz
     */
    public record Day(Days days, List<Container> containers, List<StreetChunks.Chunk> chunks, Instance instance,
            long matrixNanos) {

        public Day {
            containers = List.copyOf(containers);
            chunks = List.copyOf(chunks);
        }
    }

    /**
     * @param kgPerDay lo que le toca a la localidad por población ({@link BogotaFigures#kgPerDay})
     * @param containerKgPerDay lo que recogen sus contenedores, repartido en la semana
     * @param kgPerMeter carga de cada metro de calle local en una pasada: el resto, por los días entre dos pasadas
     * @param calendar el grupo de días de cada sector
     * @param days un grupo por instancia; falta el que no tiene nada que recoger
     */
    public record Plan(Locality locality, double kgPerDay, double containerKgPerDay, double kgPerMeter,
            Map<String, Days> calendar, List<Day> days) {

        public Plan {
            calendar = Map.copyOf(calendar);
            days = List.copyOf(days);
        }
    }

    private DoorToDoor() {
    }

    /**
     * Con la base del operador de la ASE y la portería de Doña Juana.
     *
     * @param containers los de la localidad en todos sus turnos de cargue lateral
     * @param suffix lo que dice el nombre de la instancia sobre los contenedores, p. ej. {@code -s7-f100}
     */
    public static Plan plan(RoadGraph graph, ServiceAreas.ServiceArea area, Locality locality, SectorOf sectors,
            List<Container> containers, String suffix) {
        if (!area.localities().contains(locality)) {
            throw new IllegalArgumentException(locality.name() + " no es de " + area.name());
        }
        return plan(graph, locality, Localities.bogota(), sectors, containers,
                SideLoading.of(area).collectionsPerWeek(), vertex(graph, area.base().osmNode(), "la base"),
                vertex(graph, ServiceAreas.DISPOSAL_OSM_NODE, "la portería del relleno"),
                BogotaFigures.kgPerDay(locality), suffix);
    }

    /**
     * Los contenedores que se recogen seis veces por semana van en los dos grupos; los de tres, en el de su sector,
     * como las calles.
     */
    static Plan plan(RoadGraph graph, Locality locality, Localities localities, SectorOf sectors,
            List<Container> containers, int containersPerWeek, int depot, int gate, double kgPerDay, String suffix) {
        if (locality.rural()) {
            throw new IllegalArgumentException(locality.name() + " es rural: no tiene puerta a puerta");
        }
        if (containersPerWeek != COLLECTIONS_PER_WEEK && containersPerWeek != 2 * COLLECTIONS_PER_WEEK) {
            throw new IllegalArgumentException("contenedores " + containersPerWeek + " veces por semana no caben en"
                    + " los grupos de días del puerta a puerta");
        }
        IntPredicate streets = shape -> graph.shapeInLocality(shape)
                && localities.ofShape(graph, shape).equals(locality);
        List<StreetChunks.Chunk> chunks = StreetChunks.of(graph, streets, MAX_CHUNK_METERS);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(locality.name() + " no tiene calles locales en el grafo");
        }

        long containerKg = 0;
        for (Container container : containers) {
            containerKg += container.kg();
        }
        double containerKgPerDay = containerKg * containersPerWeek / 7.0;
        if (containerKgPerDay >= kgPerDay) {
            throw new IllegalStateException(String.format(Locale.ROOT, "los contenedores de %s recogen %.1f t/día y"
                    + " a la localidad le tocan %.1f", locality.name(), containerKgPerDay / 1000, kgPerDay / 1000));
        }
        long meters = 0;
        for (StreetChunks.Chunk chunk : chunks) {
            meters += chunk.meters();
        }
        double kgPerMeter = (kgPerDay - containerKgPerDay) * 7 / COLLECTIONS_PER_WEEK / meters;

        String[] chunkSector = new String[chunks.size()];
        String[] containerSector = new String[containers.size()];
        Map<String, Long> metersBySector = new TreeMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            int entry = chunks.get(i).entry();
            chunkSector[i] = sectors.at(graph.latE7(entry), graph.lonE7(entry));
            metersBySector.merge(chunkSector[i], (long) chunks.get(i).meters(), Long::sum);
        }
        for (int i = 0; i < containers.size(); i++) {
            containerSector[i] = sectors.at(containers.get(i).latE7(), containers.get(i).lonE7());
            metersBySector.merge(containerSector[i], 0L, Long::sum);
        }
        Map<String, Days> calendar = calendar(metersBySector);

        List<Day> days = new ArrayList<>();
        for (Days group : Days.values()) {
            List<Container> dayContainers = new ArrayList<>();
            for (int i = 0; i < containers.size(); i++) {
                if (containersPerWeek == 2 * COLLECTIONS_PER_WEEK || calendar.get(containerSector[i]) == group) {
                    dayContainers.add(containers.get(i));
                }
            }
            List<StreetChunks.Chunk> dayChunks = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                if (calendar.get(chunkSector[i]) == group) {
                    dayChunks.add(chunks.get(i));
                }
            }
            if (dayContainers.isEmpty() && dayChunks.isEmpty()) {
                continue;
            }
            // El sufijo -n<nodos> es el mismo de las instancias X: el arnés ordena por él.
            String name = locality.name().replace(" ", "") + group.code() + "-n"
                    + (dayContainers.size() + dayChunks.size() + 1) + suffix;
            long started = System.nanoTime();
            Instance instance = instance(graph, dayContainers, dayChunks, depot, gate, COMPACTOR_KG, kgPerMeter,
                    name);
            days.add(new Day(group, dayContainers, dayChunks, instance, System.nanoTime() - started));
        }
        return new Plan(locality, kgPerDay, containerKgPerDay, kgPerMeter, calendar, days);
    }

    /**
     * Reparte los sectores entre los grupos de días para que queden parejos en metros de calle: del más largo al más
     * corto, cada uno al grupo que lleva menos. Determinista: a igual longitud, por código de sector.
     */
    static Map<String, Days> calendar(Map<String, Long> metersBySector) {
        List<Map.Entry<String, Long>> sectors = new ArrayList<>(metersBySector.entrySet());
        sectors.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        Days[] groups = Days.values();
        long[] load = new long[groups.length];
        Map<String, Days> calendar = new TreeMap<>();
        for (Map.Entry<String, Long> sector : sectors) {
            int lightest = 0;
            for (int g = 1; g < groups.length; g++) {
                if (load[g] < load[lightest]) {
                    lightest = g;
                }
            }
            load[lightest] += sector.getValue();
            calendar.put(sector.getKey(), groups[lightest]);
        }
        return calendar;
    }

    /**
     * Nodo 0 es la base de ida y la portería de vuelta, como en el cargue lateral. Un contenedor se visita en su
     * vértice. Llegar a un trozo cuesta el camino hasta su entrada más lo que mide recorrerlo recogiendo; de él se sale
     * por su último vértice. Un trozo carga sus metros por {@code kgPerMeter}, y al menos 1 kg.
     */
    public static Instance instance(RoadGraph graph, List<Container> containers, List<StreetChunks.Chunk> chunks,
            int depot, int gate, int capacity, double kgPerMeter, String name) {
        int n = containers.size() + chunks.size() + 1;
        int[] demands = new int[n];
        int[] entries = new int[n];
        int[] exits = new int[n];
        int[] collecting = new int[n];
        entries[0] = gate;
        exits[0] = depot;
        for (int i = 1; i <= containers.size(); i++) {
            Container container = containers.get(i - 1);
            demands[i] = container.kg();
            entries[i] = container.vertex();
            exits[i] = container.vertex();
        }
        for (int i = containers.size() + 1; i < n; i++) {
            StreetChunks.Chunk chunk = chunks.get(i - containers.size() - 1);
            demands[i] = Math.max(1, (int) Math.round(chunk.meters() * kgPerMeter));
            entries[i] = chunk.entry();
            exits[i] = chunk.exit();
            collecting[i] = chunk.meters();
        }
        for (int i = 1; i < n; i++) {
            if (demands[i] > capacity) {
                throw new IllegalStateException("el nodo " + i + " carga " + demands[i] + " kg y no cabe en un camión");
            }
        }
        int[][] distances = new int[n][n];
        AtomicInteger nextRow = new AtomicInteger();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Runnable rows = () -> {
            ShortestPaths paths = new ShortestPaths(graph, ShortestPaths.Weight.METERS);
            for (int i = nextRow.getAndIncrement(); i < n && failure.get() == null; i = nextRow.getAndIncrement()) {
                paths.distances(exits[i], entries, distances[i]);
                for (int j = 0; j < n; j++) {
                    if (distances[i][j] == ShortestPaths.UNREACHABLE) {
                        failure.compareAndSet(null, new IllegalStateException(
                                "no hay camino de " + exits[i] + " a " + entries[j]));
                        return;
                    }
                    distances[i][j] = i == j ? 0 : distances[i][j] + collecting[j];
                }
            }
        };
        Thread[] threads = new Thread[Runtime.getRuntime().availableProcessors()];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = Thread.ofPlatform().start(rows);
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return new Instance(Objects.requireNonNull(name, "name"), capacity, demands, DistanceMatrix.of(distances));
    }

    private static int vertex(RoadGraph graph, long osmNode, String what) {
        return graph.vertexOf(osmNode).orElseThrow(() -> new IllegalStateException(
                "el grafo no tiene " + what + " (nodo " + osmNode + "); reconstrúyelo"));
    }
}
