package me.ymid.rutea.bench.timefold;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;

public class RoutingConstraints implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
            factory.forEach(Vehicle.class)
                    .filter(vehicle -> vehicle.load() > vehicle.capacity())
                    .penalize(HardSoftScore.ONE_HARD, vehicle -> vehicle.load() - vehicle.capacity())
                    .asConstraint("capacidad"),
            factory.forEach(Vehicle.class)
                    .filter(vehicle -> !vehicle.visits.isEmpty())
                    .penalize(HardSoftScore.ONE_SOFT, Vehicle::distance)
                    .asConstraint("distancia"),
        };
    }
}
