package me.ymid.rutea.bench.timefold;

import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.variable.PlanningListVariable;
import me.ymid.rutea.solver.Instance;

@PlanningEntity
public class Vehicle {

    @PlanningId
    public int id;

    private Instance instance;

    @PlanningListVariable
    public List<Visit> visits = new ArrayList<>();

    public Vehicle() {
    }

    public Vehicle(int id, Instance instance) {
        this.id = id;
        this.instance = instance;
    }

    public int load() {
        int load = 0;
        for (Visit visit : visits) {
            load += visit.demand();
        }
        return load;
    }

    public int capacity() {
        return instance.capacity();
    }

    public long distance() {
        long total = 0;
        int previous = 0;
        for (Visit visit : visits) {
            total += instance.distance(previous, visit.node());
            previous = visit.node();
        }
        return total + instance.distance(previous, 0);
    }
}
