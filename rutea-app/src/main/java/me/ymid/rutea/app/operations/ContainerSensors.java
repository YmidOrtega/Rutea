package me.ymid.rutea.app.operations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.SplittableRandom;

import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.sim.FillSensor;

/**
 * Sensores de llenado simulados en los contenedores de un escenario. Cada contenedor genera su carga por
 * recolección repartida en 7/6 días (se recoge de lunes a sábado) y se vacía cuando un camión llega a él. Antes del
 * turno se vació en la recolección anterior, a la misma hora de su visita del plan, uno o dos días antes.
 */
public final class ContainerSensors {

    /**
     * @param daysSinceLastCollection 1 un día cualquiera; 2 un lunes, después del domingo sin recolección
     */
    public record Config(double densityKgPerM3, int daysSinceLastCollection, long seed) {

        public static Config typical(long seed) {
            return new Config(BogotaFigures.LOOSE_WASTE_KG_PER_M3, 1, seed);
        }
    }

    private final FillSensor[] sensors;
    private final int[] liters;
    private final double[] previousEmptied;

    private ContainerSensors(FillSensor[] sensors, int[] liters, double[] previousEmptied) {
        this.sensors = sensors;
        this.liters = liters;
        this.previousEmptied = previousEmptied;
    }

    /**
     * @param plannedVisitSeconds hora de la visita del plan de cada contenedor, en el orden de {@code containers}
     * @param sizes litros posibles; cada contenedor toma uno al azar con la semilla de {@code config}
     * @param collectionsPerWeek veces por semana que se recoge: la carga de cada contenedor es la de una recolección
     */
    public static ContainerSensors of(List<Container> containers, int[] plannedVisitSeconds, List<Integer> sizes,
            int collectionsPerWeek, Config config) {
        SplittableRandom random = new SplittableRandom(config.seed());
        int n = containers.size();
        FillSensor[] sensors = new FillSensor[n];
        int[] liters = new int[n];
        double[] previous = new double[n];
        for (int i = 0; i < n; i++) {
            liters[i] = sizes.get(random.nextInt(sizes.size()));
            double kgPerDay = containers.get(i).kg() * (double) collectionsPerWeek / 7;
            sensors[i] = FillSensor.of(liters[i], config.densityKgPerM3(), kgPerDay);
            previous[i] = plannedVisitSeconds[i] - config.daysSinceLastCollection() * 86_400.0;
        }
        return new ContainerSensors(sensors, liters, previous);
    }

    public int size() {
        return sensors.length;
    }

    public int liters(int container) {
        return liters[container];
    }

    public FillSensor sensor(int container) {
        return sensors[container];
    }

    public double fill(int container, double[] visits, double seconds) {
        double emptied = previousEmptied[container];
        for (double visit : visits) {
            if (visit <= seconds) {
                emptied = visit;
            }
        }
        return sensors[container].fill(seconds - emptied);
    }

    /** La primera hora en {@code (from, to]} en que el contenedor llega al 100 % sin que un camión llegue antes. */
    public OptionalDouble fullBetween(int container, double[] visits, double from, double to) {
        double full = sensors[container].secondsToFull();
        double emptied = previousEmptied[container];
        for (int k = 0; k <= visits.length; k++) {
            double next = k < visits.length ? visits[k] : Double.POSITIVE_INFINITY;
            double crossing = emptied + full;
            if (crossing > from && crossing <= to && crossing < next) {
                return OptionalDouble.of(crossing);
            }
            if (next > to) {
                break;
            }
            emptied = next;
        }
        return OptionalDouble.empty();
    }
}
