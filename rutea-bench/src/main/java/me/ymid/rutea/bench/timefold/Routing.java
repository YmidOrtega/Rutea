package me.ymid.rutea.bench.timefold;

import java.util.List;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardSoftScore;

// Campos públicos: Timefold los lee y escribe por reflexión y exige setters o acceso público
@PlanningSolution
public class Routing {

    @ProblemFactCollectionProperty
    @ValueRangeProvider
    public List<Visit> visits;

    @PlanningEntityCollectionProperty
    public List<Vehicle> vehicles;

    @PlanningScore
    public HardSoftScore score;

    public Routing() {
    }

    public Routing(List<Visit> visits, List<Vehicle> vehicles) {
        this.visits = visits;
        this.vehicles = vehicles;
    }
}
