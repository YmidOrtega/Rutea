package me.ymid.rutea.geo;

import java.util.Objects;

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

    RoadGraph(long[] osmNodeIds, int[] latE7, int[] lonE7, int[] firstEdge, int[] edgeTarget, int[] edgeMeters,
            int[] edgeSeconds, byte[] edgeRoadClass, int[] edgeShape, int[] firstShapePoint, int[] shapeLatE7,
            int[] shapeLonE7) {
        int vertices = osmNodeIds.length;
        int edges = edgeTarget.length;
        if (latE7.length != vertices || lonE7.length != vertices || firstEdge.length != vertices + 1
                || firstEdge[vertices] != edges || edgeMeters.length != edges || edgeSeconds.length != edges
                || edgeRoadClass.length != edges || edgeShape.length != edges
                || shapeLatE7.length != shapeLonE7.length
                || firstShapePoint[firstShapePoint.length - 1] != shapeLatE7.length) {
            throw new IllegalArgumentException("arreglos del grafo con longitudes incoherentes");
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
}
