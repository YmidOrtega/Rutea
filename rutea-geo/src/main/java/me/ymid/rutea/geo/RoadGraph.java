package me.ymid.rutea.geo;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

import me.ymid.rutea.geo.geometry.Haversine;

/**
 * Grafo vial dirigido en arreglos compactos (CSR). Los vértices son intersecciones y extremos de vía;
 * los arcos que salen de {@code v} son {@code firstEdge[v]} hasta {@code firstEdge[v + 1] - 1}.
 *
 * <p>Cada arco apunta a un trazo con la geometría de la calle. Una calle de doble sentido da dos arcos
 * sobre el mismo trazo: {@code edgeShape[e] >= 0} lo recorre en su sentido y {@code ~edgeShape[e]} al
 * revés.
 */
public final class RoadGraph {

    private final long[] osmNodeIds;
    private final int[] latE7;
    private final int[] lonE7;
    private final int[] firstEdge;
    private final int[] edgeTarget;
    private final int[] edgeMeters;
    private final int[] edgeSeconds;
    private final byte[] edgeRoadClass;
    private final int[] edgeShape;
    private final int[] firstShapePoint;
    private final int[] shapeLatE7;
    private final int[] shapeLonE7;
    private final boolean[] shapeInLocality;
    private volatile RoadGraph reversed;

    RoadGraph(long[] osmNodeIds, int[] latE7, int[] lonE7, int[] firstEdge, int[] edgeTarget, int[] edgeMeters,
            int[] edgeSeconds, byte[] edgeRoadClass, int[] edgeShape, int[] firstShapePoint, int[] shapeLatE7,
            int[] shapeLonE7, boolean[] shapeInLocality) {
        int vertices = osmNodeIds.length;
        int edges = edgeTarget.length;
        if (latE7.length != vertices || lonE7.length != vertices || firstEdge.length != vertices + 1
                || firstEdge[vertices] != edges || edgeMeters.length != edges || edgeSeconds.length != edges
                || edgeRoadClass.length != edges || edgeShape.length != edges
                || shapeLatE7.length != shapeLonE7.length
                || firstShapePoint[firstShapePoint.length - 1] != shapeLatE7.length
                || shapeInLocality.length != firstShapePoint.length - 1) {
            throw new IllegalArgumentException("arreglos del grafo con longitudes incoherentes");
        }
        for (int e = 0; e < edges; e++) {
            if (edgeMeters[e] < 1) {
                throw new IllegalArgumentException("el arco " + e + " mide " + edgeMeters[e] + " m; el mínimo es 1");
            }
        }
        this.osmNodeIds = osmNodeIds;
        this.latE7 = latE7;
        this.lonE7 = lonE7;
        this.firstEdge = firstEdge;
        this.edgeTarget = edgeTarget;
        this.edgeMeters = edgeMeters;
        this.edgeSeconds = edgeSeconds;
        this.edgeRoadClass = edgeRoadClass;
        this.edgeShape = Objects.requireNonNull(edgeShape);
        this.firstShapePoint = firstShapePoint;
        this.shapeLatE7 = shapeLatE7;
        this.shapeLonE7 = shapeLonE7;
        this.shapeInLocality = shapeInLocality;
    }

    public int vertexCount() {
        return osmNodeIds.length;
    }

    public int edgeCount() {
        return edgeTarget.length;
    }

    public int shapeCount() {
        return firstShapePoint.length - 1;
    }

    public long osmNodeId(int vertex) {
        return osmNodeIds[vertex];
    }

    /** Vértice de un nodo de OSM. Los vértices van ordenados por id de OSM, así que se busca por bisección. */
    public OptionalInt vertexOf(long osmNodeId) {
        int index = Arrays.binarySearch(osmNodeIds, osmNodeId);
        return index >= 0 ? OptionalInt.of(index) : OptionalInt.empty();
    }

    public int latE7(int vertex) {
        return latE7[vertex];
    }

    public int lonE7(int vertex) {
        return lonE7[vertex];
    }

    public int firstEdge(int vertex) {
        return firstEdge[vertex];
    }

    /** Uno más que el último arco que sale de {@code vertex}. */
    public int endEdge(int vertex) {
        return firstEdge[vertex + 1];
    }

    public int target(int edge) {
        return edgeTarget[edge];
    }

    /** Origen del arco. El CSR no lo guarda: se busca por bisección el vértice cuyo rango lo contiene. */
    public int source(int edge) {
        int lo = 0;
        int hi = vertexCount() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (firstEdge[mid] <= edge) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    public int meters(int edge) {
        return edgeMeters[edge];
    }

    public int seconds(int edge) {
        return edgeSeconds[edge];
    }

    public RoadClass roadClass(int edge) {
        return RoadClass.values()[edgeRoadClass[edge]];
    }

    /** Trazo del arco; negativo ({@code ~trazo}) si el arco lo recorre al revés. */
    public int shape(int edge) {
        return edgeShape[edge];
    }

    public int firstShapePoint(int shape) {
        return firstShapePoint[shape];
    }

    public int endShapePoint(int shape) {
        return firstShapePoint[shape + 1];
    }

    public int shapeLatE7(int point) {
        return shapeLatE7[point];
    }

    public int shapeLonE7(int point) {
        return shapeLonE7[point];
    }

    /** Si el trazo está dentro de la localidad (por su punto medio), no en el corredor hacia el destino. */
    public boolean shapeInLocality(int shape) {
        return shapeInLocality[shape];
    }

    /** Vértice más cercano a un punto, en línea recta. Recorre todos los vértices: es para consultas sueltas. */
    public int nearestVertex(int latE7, int lonE7) {
        int best = -1;
        double bestMeters = Double.POSITIVE_INFINITY;
        for (int v = 0; v < vertexCount(); v++) {
            double meters = Haversine.meters(latE7, lonE7, this.latE7[v], this.lonE7[v]);
            if (meters < bestMeters) {
                bestMeters = meters;
                best = v;
            }
        }
        return best;
    }

    public int nearestShape(int latE7, int lonE7) {
        double cosLat = Math.cos(Math.toRadians(latE7 / 1e7));
        int best = -1;
        double bestSquared = Double.POSITIVE_INFINITY;
        for (int shape = 0; shape < shapeCount(); shape++) {
            for (int p = firstShapePoint[shape] + 1; p < firstShapePoint[shape + 1]; p++) {
                double ax = (shapeLonE7[p - 1] - lonE7) * cosLat;
                double ay = shapeLatE7[p - 1] - latE7;
                double bx = (shapeLonE7[p] - lonE7) * cosLat;
                double by = shapeLatE7[p] - latE7;
                double dx = bx - ax;
                double dy = by - ay;
                double length = dx * dx + dy * dy;
                double t = length == 0 ? 0 : Math.clamp(-(ax * dx + ay * dy) / length, 0, 1);
                double x = ax + t * dx;
                double y = ay + t * dy;
                if (x * x + y * y < bestSquared) {
                    bestSquared = x * x + y * y;
                    best = shape;
                }
            }
        }
        return best;
    }

    public int[] edgesOn(int shape) {
        IntList edges = new IntList(2);
        for (int e = 0; e < edgeCount(); e++) {
            if (edgeShape[e] == shape || edgeShape[e] == ~shape) {
                edges.add(e);
            }
        }
        return edges.toArray();
    }

    /** Vértices y trazos conservan sus índices; los arcos, no. */
    public RoadGraph closing(int... shapes) {
        boolean[] closed = new boolean[shapeCount()];
        for (int shape : shapes) {
            closed[shape] = true;
        }
        int[] newFirstEdge = new int[firstEdge.length];
        IntList kept = new IntList(edgeCount());
        for (int v = 0; v < vertexCount(); v++) {
            newFirstEdge[v] = kept.size();
            for (int e = firstEdge[v]; e < firstEdge[v + 1]; e++) {
                int shape = edgeShape[e];
                if (!closed[shape < 0 ? ~shape : shape]) {
                    kept.add(e);
                }
            }
        }
        newFirstEdge[vertexCount()] = kept.size();
        return withEdges(newFirstEdge, kept.toArray(), null);
    }

    public RoadGraph reversed() {
        RoadGraph result = reversed;
        if (result == null) {
            int[] newFirstEdge = new int[firstEdge.length];
            for (int e = 0; e < edgeCount(); e++) {
                newFirstEdge[edgeTarget[e] + 1]++;
            }
            for (int v = 0; v < vertexCount(); v++) {
                newFirstEdge[v + 1] += newFirstEdge[v];
            }
            int[] next = Arrays.copyOf(newFirstEdge, vertexCount());
            int[] order = new int[edgeCount()];
            int[] sources = new int[edgeCount()];
            for (int v = 0; v < vertexCount(); v++) {
                for (int e = firstEdge[v]; e < firstEdge[v + 1]; e++) {
                    int slot = next[edgeTarget[e]]++;
                    order[slot] = e;
                    sources[slot] = v;
                }
            }
            result = withEdges(newFirstEdge, order, sources);
            result.reversed = this;
            reversed = result;
        }
        return result;
    }

    /** Con {@code sources}, cada arco se invierte hacia su origen; con {@code null}, se copia. */
    private RoadGraph withEdges(int[] newFirstEdge, int[] original, int[] sources) {
        boolean flip = sources != null;
        int count = original.length;
        int[] target = new int[count];
        int[] meters = new int[count];
        int[] seconds = new int[count];
        byte[] roadClass = new byte[count];
        int[] shape = new int[count];
        for (int i = 0; i < count; i++) {
            int e = original[i];
            target[i] = flip ? sources[i] : edgeTarget[e];
            meters[i] = edgeMeters[e];
            seconds[i] = edgeSeconds[e];
            roadClass[i] = edgeRoadClass[e];
            shape[i] = flip ? ~edgeShape[e] : edgeShape[e];
        }
        return new RoadGraph(osmNodeIds, latE7, lonE7, newFirstEdge, target, meters, seconds, roadClass, shape,
                firstShapePoint, shapeLatE7, shapeLonE7, shapeInLocality);
    }

    long[] osmNodeIds() {
        return osmNodeIds;
    }

    int[] latE7() {
        return latE7;
    }

    int[] lonE7() {
        return lonE7;
    }

    int[] firstEdge() {
        return firstEdge;
    }

    int[] edgeTarget() {
        return edgeTarget;
    }

    int[] edgeMeters() {
        return edgeMeters;
    }

    int[] edgeSeconds() {
        return edgeSeconds;
    }

    byte[] edgeRoadClass() {
        return edgeRoadClass;
    }

    int[] edgeShape() {
        return edgeShape;
    }

    int[] firstShapePoint() {
        return firstShapePoint;
    }

    int[] shapeLatE7() {
        return shapeLatE7;
    }

    int[] shapeLonE7() {
        return shapeLonE7;
    }

    boolean[] shapeInLocality() {
        return shapeInLocality;
    }
}
