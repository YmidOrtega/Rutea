package me.ymid.rutea.app.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import me.ymid.rutea.app.scenario.Sectors;
import me.ymid.rutea.domain.WasteStream;

public final class SectorSchedule {

    /** Un vehículo que vuelve al sector después de este tiempo hace otra pasada, casi siempre en otro viaje. */
    static final int PASS_GAP_SECONDS = 30 * 60;

    /** Las ventanas se publican en cuartos de hora, hacia afuera: nadie saca la bolsa tarde por un redondeo. */
    static final int ROUND_SECONDS = 15 * 60;

    private SectorSchedule() {
    }

    /**
     * Cuándo pasa cada vehículo por cada UPZ, según las llegadas planeadas: una pasada va de la primera a la última
     * llegada a sus contenedores del sector, redondeada hacia afuera al cuarto de hora.
     */
    public static List<DemoPlan.Sector> of(Sectors sectors, List<DemoPlan.Container> containers) {
        Map<String, Map<String, List<DemoPlan.Container>>> bySector = new LinkedHashMap<>();
        sectors.upz().forEach(sector -> bySector.put(sector.ref(), new TreeMap<>()));
        bySector.put(Sectors.RURAL, new TreeMap<>());
        for (DemoPlan.Container container : containers) {
            bySector.get(container.sector()).computeIfAbsent(container.route(), route -> new ArrayList<>())
                    .add(container);
        }
        List<Sectors.Upz> all = new ArrayList<>(sectors.upz());
        all.add(new Sectors.Upz(Sectors.RURAL, Sectors.RURAL_NAME, null, List.of()));
        List<DemoPlan.Sector> schedule = new ArrayList<>();
        for (Sectors.Upz sector : all) {
            if (bySector.get(sector.ref()).isEmpty()) {
                continue;
            }
            List<DemoPlan.Pass> passes = new ArrayList<>();
            bySector.get(sector.ref()).forEach((route, visited) -> {
                visited.sort(Comparator.comparingInt(DemoPlan.Container::visitSeconds));
                WasteStream stream = visited.getFirst().stream();
                int from = visited.getFirst().visitSeconds();
                int last = from;
                int count = 0;
                for (DemoPlan.Container container : visited) {
                    if (container.visitSeconds() - last > PASS_GAP_SECONDS) {
                        passes.add(pass(stream, route, from, last, count));
                        from = container.visitSeconds();
                        count = 0;
                    }
                    last = container.visitSeconds();
                    count++;
                }
                passes.add(pass(stream, route, from, last, count));
            });
            passes.sort(Comparator.comparingInt(DemoPlan.Pass::fromSeconds).thenComparing(DemoPlan.Pass::route));
            schedule.add(new DemoPlan.Sector(sector.ref(), sector.name(), sector.outline(), passes));
        }
        return schedule;
    }

    private static DemoPlan.Pass pass(WasteStream stream, String route, int first, int last, int containers) {
        int from = Math.floorDiv(first, ROUND_SECONDS) * ROUND_SECONDS;
        int to = Math.max(from + ROUND_SECONDS, -Math.floorDiv(-last, ROUND_SECONDS) * ROUND_SECONDS);
        return new DemoPlan.Pass(stream, route, from, to, containers);
    }
}
