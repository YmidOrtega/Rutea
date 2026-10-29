package me.ymid.rutea.app.scenario;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import me.ymid.rutea.domain.WasteStream;
import me.ymid.rutea.geo.RoadClass;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.geometry.Haversine;

/**
 * Contenedores simulados sobre las calles reales de la localidad, mientras no haya un inventario oficial
 * (UAESP o el operador del ASE).
 *
 * <p>Se reparten al azar con probabilidad proporcional a la longitud de calle local: es un indicador de
 * dónde vive la gente más débil que la vivienda. Los edificios de OSM no sirven para esto en Suba, porque
 * están mapeados de forma incompleta y despareja. Cada contenedor se asigna al extremo más cercano de su
 * tramo, que es donde el camión lo visita en el grafo.
 *
 * <p>La carga de cada contenedor varía al azar entre la mitad y vez y media de la media. Es un supuesto:
 * no hay datos de llenado por contenedor. La media sale de {@link BogotaFigures}.
 */
public final class SimulatedContainers {

    /** Dos contenedores nunca quedan más cerca que esto, para no apilarlos en la misma esquina. */
    public static final int MIN_SPACING_METERS = 50;

    /** Calles donde se pone un contenedor: ni avenidas ni vías de servicio. */
    static final Set<RoadClass> LOCAL_STREETS =
            EnumSet.of(RoadClass.TERTIARY, RoadClass.UNCLASSIFIED, RoadClass.RESIDENTIAL, RoadClass.LIVING_STREET);

    public record Container(String id, WasteStream stream, int latE7, int lonE7, int vertex, int shape, int kg) {
    }

    private SimulatedContainers() {
    }

    public static List<Container> place(RoadGraph graph, WasteStream stream, int count, double meanKg, long seed) {
        if (meanKg <= 0) {
            throw new IllegalArgumentException("carga media no positiva: " + meanKg);
        }
        Streets streets = Streets.of(graph);
        if (streets.totalMeters == 0) {
            throw new IllegalArgumentException("el grafo no tiene calles locales dentro de la localidad");
        }
        SplittableRandom random = new SplittableRandom(seed);
        SpacingGrid placed = new SpacingGrid(MIN_SPACING_METERS);
        List<Container> containers = new ArrayList<>(count);
        int maxAttempts = 100 * count;
        for (int attempt = 0; containers.size() < count; attempt++) {
            if (attempt == maxAttempts) {
                throw new IllegalArgumentException("solo cupieron " + containers.size() + " de " + count
                        + " contenedores separados por " + MIN_SPACING_METERS + " m");
            }
            int s = streets.pick(random.nextDouble() * streets.totalMeters);
            int shape = streets.shapes[s];
            double along = random.nextDouble() * streets.lengths[s];
            int[] point = pointAlong(graph, shape, along);
            if (!placed.isFree(point[0], point[1])) {
                continue;
            }
            placed.add(point[0], point[1]);
            int vertex = along < streets.lengths[s] / 2 ? streets.startVertex[s] : streets.endVertex[s];
            String id = String.format("%s%04d", prefix(stream), containers.size() + 1);
            int kg = (int) Math.max(1, Math.round(meanKg * (0.5 + random.nextDouble())));
            containers.add(new Container(id, stream, point[0], point[1], vertex, shape, kg));
        }
        return containers;
    }

    private static String prefix(WasteStream stream) {
        return switch (stream) {
            case NON_RECYCLABLE -> "N";
            case RECYCLABLE -> "A";
            case ORGANIC -> "O";
        };
    }

    /** Punto a {@code meters} del inicio del trazo, interpolado sobre su geometría. */
    static int[] pointAlong(RoadGraph graph, int shape, double meters) {
        int first = graph.firstShapePoint(shape);
        int end = graph.endShapePoint(shape);
        double remaining = meters;
        for (int p = first + 1; p < end; p++) {
            int lat0 = graph.shapeLatE7(p - 1);
            int lon0 = graph.shapeLonE7(p - 1);
            int lat1 = graph.shapeLatE7(p);
            int lon1 = graph.shapeLonE7(p);
            double segment = Haversine.meters(lat0, lon0, lat1, lon1);
            if (remaining <= segment || p == end - 1) {
                double t = segment == 0 ? 0 : Math.min(1, remaining / segment);
                return new int[] {(int) Math.round(lat0 + t * (lat1 - lat0)), (int) Math.round(lon0 + t * (lon1 - lon0))};
            }
            remaining -= segment;
        }
        return new int[] {graph.shapeLatE7(first), graph.shapeLonE7(first)};
    }

    /** Trazos elegibles con su longitud acumulada, para elegir uno con probabilidad proporcional al largo. */
    private static final class Streets {

        private final int[] shapes;
        private final double[] lengths;
        private final double[] cumulative;
        private final int[] startVertex;
        private final int[] endVertex;
        private final double totalMeters;

        private Streets(int[] shapes, double[] lengths, int[] startVertex, int[] endVertex) {
            this.shapes = shapes;
            this.lengths = lengths;
            this.startVertex = startVertex;
            this.endVertex = endVertex;
            cumulative = new double[lengths.length];
            double total = 0;
            for (int i = 0; i < lengths.length; i++) {
                total += lengths[i];
                cumulative[i] = total;
            }
            totalMeters = total;
        }

        static Streets of(RoadGraph graph) {
            // Un trazo de doble sentido aparece en dos arcos: se toma una vez, con sus extremos en su sentido.
            Map<Integer, int[]> endpoints = new HashMap<>();
            for (int e = 0; e < graph.edgeCount(); e++) {
                int shape = graph.shape(e);
                int base = shape >= 0 ? shape : ~shape;
                if (endpoints.containsKey(base) || !graph.shapeInLocality(base)
                        || !LOCAL_STREETS.contains(graph.roadClass(e))) {
                    continue;
                }
                int source = graph.source(e);
                int target = graph.target(e);
                endpoints.put(base, shape >= 0 ? new int[] {source, target} : new int[] {target, source});
            }
            int[] shapes = endpoints.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
            double[] lengths = new double[shapes.length];
            int[] start = new int[shapes.length];
            int[] end = new int[shapes.length];
            for (int i = 0; i < shapes.length; i++) {
                lengths[i] = length(graph, shapes[i]);
                start[i] = endpoints.get(shapes[i])[0];
                end[i] = endpoints.get(shapes[i])[1];
            }
            return new Streets(shapes, lengths, start, end);
        }

        int pick(double meters) {
            int lo = 0;
            int hi = cumulative.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cumulative[mid] <= meters) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        private static double length(RoadGraph graph, int shape) {
            double meters = 0;
            for (int p = graph.firstShapePoint(shape) + 1; p < graph.endShapePoint(shape); p++) {
                meters += Haversine.meters(graph.shapeLatE7(p - 1), graph.shapeLonE7(p - 1), graph.shapeLatE7(p),
                        graph.shapeLonE7(p));
            }
            return meters;
        }
    }

    /** Celdas del tamaño de la separación mínima: basta mirar la celda propia y las ocho vecinas. */
    private static final class SpacingGrid {

        /** Latitud hasta la que la celda de longitud alcanza a cubrir la separación mínima. */
        private static final int MAX_LATITUDE_E7 = 600_000_000;

        private final int minMeters;
        private final int latCellE7;
        private final int lonCellE7;
        private final Map<Long, List<int[]>> cells = new HashMap<>();

        SpacingGrid(int minMeters) {
            this.minMeters = minMeters;
            // Un grado de longitud mide menos metros lejos del ecuador: la celda de longitud se dimensiona
            // para 60° y así cubre la separación en cualquier ciudad más cerca del ecuador.
            latCellE7 = Math.max(1, Haversine.latitudeE7(minMeters));
            lonCellE7 = Math.max(1, Haversine.longitudeE7(minMeters, MAX_LATITUDE_E7));
        }

        boolean isFree(int latE7, int lonE7) {
            long row = Math.floorDiv(latE7, latCellE7);
            long column = Math.floorDiv(lonE7, lonCellE7);
            for (long r = row - 1; r <= row + 1; r++) {
                for (long c = column - 1; c <= column + 1; c++) {
                    for (int[] other : cells.getOrDefault(key(r, c), List.of())) {
                        if (Haversine.meters(latE7, lonE7, other[0], other[1]) < minMeters) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        void add(int latE7, int lonE7) {
            long key = key(Math.floorDiv(latE7, latCellE7), Math.floorDiv(lonE7, lonCellE7));
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[] {latE7, lonE7});
        }

        private static long key(long row, long column) {
            return row << 32 ^ (column & 0xFFFF_FFFFL);
        }
    }
}
