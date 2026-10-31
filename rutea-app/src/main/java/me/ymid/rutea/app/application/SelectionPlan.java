package me.ymid.rutea.app.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import me.ymid.rutea.app.planning.CityPlanner;
import me.ymid.rutea.app.planning.DemoPlan;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.app.scenario.SimulatedContainers;

/**
 * El cargue lateral de una selección de localidades, una entrada por ASE (ADR 0012).
 *
 * @param localities los números elegidos, en orden y sin repetir
 * @param planMs de la selección al último plan, en reloj de pared
 */
public record SelectionPlan(List<Integer> localities, List<Area> areas, double planMs) {

    public SelectionPlan {
        localities = List.copyOf(localities);
        areas = List.copyOf(areas);
    }

    /**
     * @param localities las elegidas que son de esta ASE
     * @param kgPerDay lo que recogen sus contenedores elegidos, repartido en la semana
     * @param sideLoadingShare parte de las toneladas de la ASE que es {@code kgPerDay}; vacía si el operador no publica
     *     sus toneladas
     * @param skipped turnos sin contenedores en las localidades elegidas
     */
    public record Area(int number, String name, String operator, List<Integer> localities, Fleet fleet,
            long kgPerDay, Optional<Double> sideLoadingShare, List<Shift> shifts, List<String> skipped) {

        public Area {
            localities = List.copyOf(localities);
            shifts = List.copyOf(shifts);
            skipped = List.copyOf(skipped);
        }
    }

    /**
     * @param lowerBound ningún reparto de los viajes usa menos camiones
     * @param declared los de cargue lateral que declara el operador; vacío si no los declara
     */
    public record Fleet(int trucks, int lowerBound, boolean sharedAcrossShifts, OptionalInt declared,
            Optional<String> warning) {
    }

    public record Shift(String name, String start, int seconds, DemoPlan plan) {
    }

    static SelectionPlan of(List<Locality> selection, CityPlanner.Plan plan) {
        Map<ServiceAreas.ServiceArea, List<CityPlanner.AreaPlan>> plans = new LinkedHashMap<>();
        Map<ServiceAreas.ServiceArea, List<String>> skipped = new LinkedHashMap<>();
        ServiceAreas.split(selection).keySet().forEach(area -> {
            plans.put(area, new ArrayList<>());
            skipped.put(area, new ArrayList<>());
        });
        plan.areas().forEach(areaPlan -> plans.get(areaPlan.scenario().area()).add(areaPlan));
        plan.skipped().forEach(shift -> skipped.get(shift.area()).add(shift.shift().name()));
        Map<ServiceAreas.ServiceArea, CityPlanner.Fleet> fleets = new LinkedHashMap<>();
        plan.fleets().forEach(fleet -> fleets.put(fleet.area(), fleet));

        List<Area> areas = new ArrayList<>();
        for (ServiceAreas.ServiceArea area : plans.keySet()) {
            SideLoading.Area sideLoading = SideLoading.of(area);
            List<Shift> shifts = new ArrayList<>();
            long kgPerCollection = 0;
            for (CityPlanner.AreaPlan areaPlan : plans.get(area)) {
                SideLoading.Shift shift = areaPlan.shift();
                shifts.add(new Shift(shift.name(), shift.start(), shift.seconds(), areaPlan.plan()));
                for (SimulatedContainers.Container container : areaPlan.scenario().containers()) {
                    kgPerCollection += container.kg();
                }
            }
            double kgPerDay = kgPerCollection * sideLoading.collectionsPerWeek() / 7.0;
            CityPlanner.Fleet fleet = fleets.get(area);
            Fleet summary = fleet == null ? new Fleet(0, 0, true, sideLoading.sideLoaders(), Optional.empty())
                    : new Fleet(fleet.trucks(), fleet.lowerBound(), fleet.sharedAcrossShifts(), fleet.declared(),
                            fleet.warning());
            areas.add(new Area(area.number(), area.name(), area.operator(),
                    selection.stream().filter(area.localities()::contains).map(Locality::number).toList(), summary,
                    Math.round(kgPerDay), sideLoading.share(kgPerDay), shifts, skipped.get(area)));
        }
        return new SelectionPlan(selection.stream().map(Locality::number).toList(), areas, plan.nanos() / 1e6);
    }
}
