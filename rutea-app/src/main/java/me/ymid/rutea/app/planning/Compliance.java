package me.ymid.rutea.app.planning;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.app.scenario.SubaFigures;
import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.domain.CollectionRules;
import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadClass;
import me.ymid.rutea.geo.RoadGraph;

/**
 * Revisa un plan contra R02, R03 y R07 y dice, contenedor por contenedor, qué exige la norma en esa parada.
 * No cambia las rutas: marca lo que el conductor tiene que hacer distinto (no compactar, mitigar el ruido).
 */
public final class Compliance {

    /** Lo que la norma exige en una parada. Las razones van en texto para mostrarlas tal cual. */
    public record Stop(boolean noCompaction, boolean nightNoise, List<String> reasons) {

        public Stop {
            reasons = List.copyOf(reasons);
        }
    }

    /** Una regla y cuántas paradas toca en el plan. */
    public record Check(String id, String citation, int count, String summary) {
    }

    public record Result(List<Stop> stops, List<Check> checks) {

        public Result {
            stops = List.copyOf(stops);
            checks = List.copyOf(checks);
        }
    }

    private Compliance() {
    }

    /**
     * @param visitSeconds segundos desde el inicio del turno hasta que el camión llega a cada contenedor
     */
    public static Result evaluate(SubaScenario scenario, int[] visitSeconds, String shiftStart) {
        RoadGraph graph = scenario.graph();
        RoadClass[] shapeClass = new RoadClass[graph.shapeCount()];
        for (int e = 0; e < graph.edgeCount(); e++) {
            int shape = graph.shape(e) >= 0 ? graph.shape(e) : ~graph.shape(e);
            shapeClass[shape] = graph.roadClass(e);
        }
        LocalTime start = LocalTime.parse(shiftStart);
        List<Stop> stops = new ArrayList<>();
        int noCompaction = 0;
        int nightNoise = 0;
        for (int i = 0; i < scenario.containers().size(); i++) {
            Container container = scenario.containers().get(i);
            List<String> reasons = new ArrayList<>();
            SensitiveSites.Site front = nearest(scenario.sites(), container, true);
            boolean r07 = front != null;
            if (r07) {
                reasons.add(String.format(Locale.ROOT, "R07 · sin compactación: frente a %s (%s, %.0f m)", front.label(),
                        front.kind().label(), front.metersTo(container.latE7(), container.lonE7())));
                noCompaction++;
            }
            LocalTime arrival = start.plusSeconds(visitSeconds[i]);
            boolean night = CollectionRules.NIGHT_WINDOW.contains(arrival);
            RoadClass street = shapeClass[container.shape()];
            boolean residential = street == RoadClass.RESIDENTIAL || street == RoadClass.LIVING_STREET;
            SensitiveSites.Site noisy = nearest(scenario.sites(), container, false);
            boolean r02 = night && (residential || noisy != null);
            if (r02) {
                String where = noisy != null ? "frente a " + noisy.label() + " (" + noisy.kind().label() + ")"
                        : "en calle residencial";
                reasons.add(String.format(Locale.ROOT, "R02 · %02d:%02d, de noche %s: medidas contra el ruido",
                        arrival.getHour(), arrival.getMinute(), where));
                nightNoise++;
            }
            stops.add(new Stop(r07, r02, reasons));
        }

        int frequency = SubaFigures.COLLECTIONS_PER_WEEK;
        int minimum = CollectionRules.minimumWeeklyFrequency(WasteStream.NON_RECYCLABLE).orElseThrow();
        List<Check> checks = List.of(
                new Check("R02", CollectionRules.NIGHT_NOISE.citation(), nightNoise,
                        nightNoise + " paradas entre las 21:00 y las 06:00 en calle residencial o frente a hotel,"
                                + " hospital, clínica o centro asistencial: requieren medidas contra el ruido"),
                new Check("R03", CollectionRules.MINIMUM_FREQUENCY.citation(), frequency >= minimum ? 0 : 1,
                        frequency >= minimum
                                ? "Se recoge " + frequency + " veces por semana; el mínimo es " + minimum
                                : "Se recoge " + frequency + " veces por semana, menos que el mínimo de " + minimum),
                new Check("R07", CollectionRules.NO_COMPACTION.citation(), noCompaction,
                        noCompaction + " contenedores a " + SensitiveSites.FRONT_METERS + " m o menos de un centro"
                                + " educativo, hospital, clínica o centro asistencial: ahí no se compacta"));
        return new Result(stops, checks);
    }

    private static SensitiveSites.Site nearest(List<SensitiveSites.Site> sites, Container container,
            boolean forNoCompaction) {
        SensitiveSites.Site best = null;
        double bestMeters = SensitiveSites.FRONT_METERS;
        for (SensitiveSites.Site site : sites) {
            boolean applies = forNoCompaction ? site.kind().noCompaction() : site.kind().nightNoise();
            if (!applies) {
                continue;
            }
            double meters = site.metersTo(container.latE7(), container.lonE7());
            if (meters <= bestMeters) {
                bestMeters = meters;
                best = site;
            }
        }
        return best;
    }
}
