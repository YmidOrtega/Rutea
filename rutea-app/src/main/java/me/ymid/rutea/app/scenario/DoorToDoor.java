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

    /**
     * Turnos del puerta a puerta: los de las microrrutas domiciliarias de 3 veces por semana que publica el ASE 1,
     * 06:00–16:00 y 18:00–04:00 (capa de microrrutas del operador; los mismos de {@link SideLoading#ASE_1_DAY} y
     * {@link SideLoading#ASE_1_NIGHT}). {@code PENDIENTE_VERIFICAR} en las demás ASE, que no los publican.
     */
    public static final List<SideLoading.Shift> SHIFTS = List.of(SideLoading.ASE_1_DAY, SideLoading.ASE_1_NIGHT);

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

    public record Slot(Days days, SideLoading.Shift shift) {

        public static final List<Slot> ALL = all();

        private static List<Slot> all() {
            List<Slot> slots = new ArrayList<>();
            for (Days days : Days.values()) {
                for (SideLoading.Shift shift : SHIFTS) {
                    slots.add(new Slot(days, shift));
                }
            }
            return List.copyOf(slots);
        }

        public String code() {
            return days.code() + shift.name();
        }

        public String label() {
            return String.format(Locale.ROOT, "%s, turno de %s desde las %s", days.label(),
                    shift.name().equals("Dia") ? "día" : shift.name().toLowerCase(Locale.ROOT), shift.start());
        }
    }

    @FunctionalInterface
    public interface SectorOf {
        String at(int latE7, int lonE7);
    }

    public record Turn(Slot slot, List<Container> containers, List<StreetChunks.Chunk> chunks, Instance instance,
            long matrixNanos) {

        public Turn {
            containers = List.copyOf(containers);
            chunks = List.copyOf(chunks);
        }
    }

    public record Plan(Locality locality, double kgPerDay, double containerKgPerDay, double kgPerMeter,
            Map<String, Slot> calendar, List<Turn> turns) {

        public Plan {
            calendar = Map.copyOf(calendar);
            turns = List.copyOf(turns);
        }
    }

    private DoorToDoor() {
    }

    public static Plan plan(RoadGraph graph, ServiceAreas.ServiceArea area, Locality locality, SectorOf sectors,
            Map<SideLoading.Shift, List<Container>> containers, String suffix) {
        if (!area.localities().contains(locality)) {
            throw new IllegalArgumentException(locality.name() + " no es de " + area.name());
        }
        return plan(graph, locality, Localities.bogota(), sectors, containers,
                SideLoading.of(area).collectionsPerWeek(), vertex(graph, area.base().osmNode(), "la base"),
                vertex(graph, ServiceAreas.DISPOSAL_OSM_NODE, "la portería del relleno"),
                BogotaFigures.kgPerDay(locality), suffix);
    }

    static Plan plan(RoadGraph graph, Locality locality, Localities localities, SectorOf sectors,
            Map<SideLoading.Shift, List<Container>> containers, int containersPerWeek, int depot, int gate,
            double kgPerDay, String suffix) {
        if (locality.rural()) {
            throw new IllegalArgumentException(locality.name() + " es rural: no tiene puerta a puerta");
        }
        if (containersPerWeek != COLLECTIONS_PER_WEEK && containersPerWeek != 2 * COLLECTIONS_PER_WEEK) {
            throw new IllegalArgumentException("contenedores " + containersPerWeek + " veces por semana no caben en"
                    + " los grupos de días del puerta a puerta");
        }
        List<Container> all = new ArrayList<>();
        List<SideLoading.Shift> containerShift = new ArrayList<>();
        containers.forEach((shift, list) -> {
            SideLoading.Shift turn = SHIFTS.stream().filter(candidate -> candidate.name().equals(shift.name()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("el turno " + shift.name()
                            + " de los contenedores no es de día ni de noche"));
            for (Container container : list) {
                all.add(container);
                containerShift.add(turn);
            }
        });
        IntPredicate streets = shape -> graph.shapeInLocality(shape)
                && localities.ofShape(graph, shape).equals(locality);
        List<StreetChunks.Chunk> chunks = StreetChunks.of(graph, streets, MAX_CHUNK_METERS);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException(locality.name() + " no tiene calles locales en el grafo");
        }

        long containerKg = 0;
        for (Container container : all) {
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
        String[] containerSector = new String[all.size()];
        Map<String, Long> metersBySector = new TreeMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            int entry = chunks.get(i).entry();
            chunkSector[i] = sectors.at(graph.latE7(entry), graph.lonE7(entry));
            metersBySector.merge(chunkSector[i], (long) chunks.get(i).meters(), Long::sum);
        }
        for (int i = 0; i < all.size(); i++) {
            containerSector[i] = sectors.at(all.get(i).latE7(), all.get(i).lonE7());
            metersBySector.merge(containerSector[i], 0L, Long::sum);
        }
        Map<String, Slot> calendar = calendar(metersBySector);

        List<Turn> turns = new ArrayList<>();
        for (Slot slot : Slot.ALL) {
            List<Container> slotContainers = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                boolean onTheseDays = containersPerWeek == 2 * COLLECTIONS_PER_WEEK
                        || calendar.get(containerSector[i]).days() == slot.days();
                if (onTheseDays && containerShift.get(i).equals(slot.shift())) {
                    slotContainers.add(all.get(i));
                }
            }
            List<StreetChunks.Chunk> slotChunks = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                if (calendar.get(chunkSector[i]).equals(slot)) {
                    slotChunks.add(chunks.get(i));
                }
            }
            if (slotContainers.isEmpty() && slotChunks.isEmpty()) {
                continue;
            }
            // El sufijo -n<nodos> es el mismo de las instancias X: el arnés ordena por él.
            String name = locality.name().replace(" ", "") + slot.code() + "-n"
                    + (slotContainers.size() + slotChunks.size() + 1) + suffix;
            long started = System.nanoTime();
            Instance instance = instance(graph, slotContainers, slotChunks, depot, gate, COMPACTOR_KG, kgPerMeter,
                    name);
            turns.add(new Turn(slot, slotContainers, slotChunks, instance, System.nanoTime() - started));
        }
        return new Plan(locality, kgPerDay, containerKgPerDay, kgPerMeter, calendar, turns);
    }

    static Map<String, Slot> calendar(Map<String, Long> metersBySector) {
        List<Map.Entry<String, Long>> sectors = new ArrayList<>(metersBySector.entrySet());
        sectors.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        List<Slot> slots = Slot.ALL;
        long[] load = new long[slots.size()];
        Map<String, Slot> calendar = new TreeMap<>();
        for (Map.Entry<String, Long> sector : sectors) {
            int lightest = 0;
            for (int g = 1; g < slots.size(); g++) {
                if (load[g] < load[lightest]) {
                    lightest = g;
                }
            }
            load[lightest] += sector.getValue();
            calendar.put(sector.getKey(), slots.get(lightest));
        }
        return calendar;
    }

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
