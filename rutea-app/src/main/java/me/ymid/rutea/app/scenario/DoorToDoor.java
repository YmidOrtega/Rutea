package me.ymid.rutea.app.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.function.IntPredicate;

import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.solver.Instance;

public final class DoorToDoor {

    /** {@code PENDIENTE_VERIFICAR}: veces por semana que el puerta a puerta pasa por cada calle. */
    public static final int COLLECTIONS_PER_WEEK = 3;

    /** Longitud máxima de un trozo de calle, medida en Suba (ADR 0014). */
    public static final int MAX_CHUNK_METERS = 500;

    /**
     * Un arco sin cruces más largo que esto no da a manzanas: el único que pasa de 2,5 km en las 19 localidades es la
     * vía OSM 91266728 de Bosa, 4,8 km sin pavimentar (ADR 0014).
     */
    public static final int MAX_STREET_METERS = 1_000;

    /** {@code PENDIENTE_VERIFICAR}: velocidad del compactador mientras recoge casa por casa. */
    public static final int COLLECTING_KMH = 6;

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

    public record Turn(Slot slot, List<Container> containers, List<StreetChunks.Chunk> chunks, String name) {

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

    /**
     * Los contenedores negros de la localidad por turno de cargue lateral: los reales del operador o, si no publica
     * su ubicación, los simulados de su cantidad oficial (ADR 0013).
     */
    public static Map<SideLoading.Shift, List<Container>> containers(RoadGraph graph, ServiceAreas.ServiceArea area,
            Locality locality, Map<Integer, List<OperatorContainers.Container>> real, double fill, long seed) {
        SideLoading.Area sideLoading = SideLoading.of(area);
        Map<SideLoading.Shift, List<Container>> containers = new LinkedHashMap<>();
        if (sideLoading.planned().isPresent()) {
            SideLoading.Planned planned = sideLoading.planned().get();
            containers.put(sideLoading.shifts().getFirst(), AreaScenario.plannedContainers(graph, area,
                    List.of(locality), planned.count(), planned.liters(), fill, seed));
            return containers;
        }
        List<OperatorContainers.Container> layer = real.get(area.number());
        if (layer == null) {
            throw new IllegalArgumentException("faltan los contenedores del operador de " + area.name());
        }
        for (SideLoading.Shift shift : sideLoading.shifts()) {
            containers.put(shift, AreaScenario.realContainers(graph, area, shift, List.of(locality), layer, fill));
        }
        return containers;
    }

    /**
     * Compactadores de carga trasera en el turno de la franja, encadenando viajes como los de cargue lateral
     * ({@link SideLoading#MAX_TRIPS_PER_TRUCK}).
     */
    public static ServiceAreas.Operation operation(Slot slot) {
        return new ServiceAreas.Operation(OptionalDouble.empty(), COLLECTIONS_PER_WEEK, slot.shift().start(),
                slot.shift().seconds(), COMPACTOR_KG, List.of(), SideLoading.MAX_TRIPS_PER_TRUCK);
    }

    public record Live(Plan plan, AreaScenario scenario) {
    }

    /**
     * Lo que recoge una localidad en una franja, con los contenedores de sus instancias (ver {@link #containers}) y
     * las UPZ como sectores.
     *
     * @throws IllegalArgumentException si la localidad no recoge nada en esa franja
     */
    public static Live live(RoadGraph graph, Locality locality, Slot slot,
            Map<Integer, List<OperatorContainers.Container>> real, long seed) {
        ServiceAreas.ServiceArea area = ServiceAreas.split(List.of(locality)).keySet().iterator().next();
        Plan plan = plan(graph, area, locality, Sectors.of(area)::locate, containers(graph, area, locality, real,
                SideLoading.MEAN_FILL, seed), "");
        Turn turn = plan.turns().stream().filter(candidate -> candidate.slot().equals(slot)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(locality.name() + " no recoge nada " + slot.label()));
        return new Live(plan, scenario(graph, area, plan, turn));
    }

    public static AreaScenario scenario(RoadGraph graph, ServiceAreas.ServiceArea area, Plan plan, Turn turn) {
        return AreaScenario.buildDoorToDoor(graph, area, operation(turn.slot()), turn.containers(), turn.chunks(),
                plan.kgPerMeter(), turn.name());
    }

    public static Plan plan(RoadGraph graph, ServiceAreas.ServiceArea area, Locality locality, SectorOf sectors,
            Map<SideLoading.Shift, List<Container>> containers, String suffix) {
        if (!area.localities().contains(locality)) {
            throw new IllegalArgumentException(locality.name() + " no es de " + area.name());
        }
        return plan(graph, locality, Localities.bogota(), UrbanPerimeter.bogota(), sectors, containers,
                SideLoading.of(area).collectionsPerWeek(), BogotaFigures.kgPerDay(locality), suffix);
    }

    static Plan plan(RoadGraph graph, Locality locality, Localities localities, UrbanPerimeter urban, SectorOf sectors,
            Map<SideLoading.Shift, List<Container>> containers, int containersPerWeek, double kgPerDay, String suffix) {
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
        IntPredicate streets = shape -> graph.shapeInLocality(shape) && urban.containsShape(graph, shape)
                && localities.ofShape(graph, shape).equals(locality);
        List<StreetChunks.Chunk> chunks = StreetChunks.of(graph, streets, MAX_CHUNK_METERS).stream()
                .filter(chunk -> chunk.meters() <= MAX_STREET_METERS).toList();
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
            turns.add(new Turn(slot, slotContainers, slotChunks, name));
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

    public static int collectingSeconds(int meters) {
        return (int) Math.round(meters * 3.6 / COLLECTING_KMH);
    }

    public static Instance instance(RoadGraph graph, List<Container> containers, List<StreetChunks.Chunk> chunks,
            int depot, int gate, int capacity, double kgPerMeter, String name) {
        return AreaScenario.instance(AreaScenario.travel(graph, containers, chunks, depot, gate),
                AreaScenario.demands(containers, chunks, kgPerMeter, capacity),
                AreaScenario.collecting(containers, chunks), capacity, Objects.requireNonNull(name, "name"));
    }
}
