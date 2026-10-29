package me.ymid.rutea.app.planning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.SimulatedContainers.Container;
import me.ymid.rutea.geo.geometry.Haversine;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;

/**
 * Línea base ingenua del criterio de cierre de F4: el barrido por cuadrantes, como una microruta fija por
 * sector que nadie optimizó.
 *
 * <p>La localidad se parte en cuadrados de {@code cellMeters} de lado, recorridos en serpentina de norte a sur.
 * Dentro de cada cuadrante, el camión barre franjas de {@link #LANE_METERS}, también en serpentina. Los
 * contenedores se recogen en ese orden hasta que el siguiente ya no cabe; entonces el camión va al relleno y
 * otro sigue donde quedó.
 */
public final class QuadrantSweep {

    /** Ancho de cada franja dentro de un cuadrante: unas dos cuadras de Bogotá. */
    public static final int LANE_METERS = 200;

    private QuadrantSweep() {
    }

    public static String name(int cellMeters) {
        return "barrido-cuadrantes-" + cellMeters + "m";
    }

    public static Solution solve(AreaScenario scenario, int cellMeters) {
        if (cellMeters < LANE_METERS) {
            throw new IllegalArgumentException("el cuadrante (" + cellMeters + " m) debe ser al menos una franja ("
                    + LANE_METERS + " m)");
        }
        List<Container> containers = scenario.containers();
        int maxLat = containers.stream().mapToInt(Container::latE7).max().orElseThrow();
        int minLat = containers.stream().mapToInt(Container::latE7).min().orElseThrow();
        int minLon = containers.stream().mapToInt(Container::lonE7).min().orElseThrow();
        int midLat = (int) (((long) maxLat + minLat) / 2);
        int cellLat = Haversine.latitudeE7(cellMeters);
        int cellLon = Haversine.longitudeE7(cellMeters, midLat);
        int laneLat = Haversine.latitudeE7(LANE_METERS);

        long[][] keys = new long[containers.size()][];
        for (int i = 0; i < containers.size(); i++) {
            Container c = containers.get(i);
            long row = (maxLat - c.latE7()) / cellLat;
            long column = (c.lonE7() - minLon) / cellLon;
            long cellTop = maxLat - row * cellLat;
            long lane = (cellTop - c.latE7()) / laneLat;
            // Serpentina: las filas impares de cuadrantes van de este a oeste, y las franjas impares también.
            boolean westward = (row % 2 == 1) != (lane % 2 == 1);
            keys[i] = new long[] {row, row % 2 == 1 ? -column : column, lane,
                    westward ? -c.lonE7() : c.lonE7(), i};
        }
        Integer[] order = new Integer[containers.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparing(i -> keys[i], Arrays::compare));

        Instance instance = scenario.instance();
        List<int[]> routes = new ArrayList<>();
        List<Integer> route = new ArrayList<>();
        int load = 0;
        for (int i : order) {
            int node = i + 1;
            if (load + instance.demand(node) > instance.capacity() && !route.isEmpty()) {
                routes.add(route.stream().mapToInt(Integer::intValue).toArray());
                route = new ArrayList<>();
                load = 0;
            }
            route.add(node);
            load += instance.demand(node);
        }
        if (!route.isEmpty()) {
            routes.add(route.stream().mapToInt(Integer::intValue).toArray());
        }
        return Solution.of(routes.toArray(int[][]::new));
    }
}
