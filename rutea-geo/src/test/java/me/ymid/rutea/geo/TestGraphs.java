package me.ymid.rutea.geo;

import java.util.Arrays;
import java.util.Comparator;

/** Grafos chicos hechos a mano: cada arco es {origen, destino, metros, segundos}. */
final class TestGraphs {

    private TestGraphs() {
    }

    static RoadGraph of(int vertexCount, int[]... edges) {
        int[] shapes = new int[edges.length];
        Arrays.setAll(shapes, i -> i);
        return build(vertexCount, edges, shapes);
    }

    /** Calles de doble sentido {a, b, metros, segundos}: dos arcos sobre un mismo trazo, como en RoadGraphBuilder. */
    static RoadGraph streets(int vertexCount, int[]... streets) {
        int[][] edges = new int[2 * streets.length][];
        int[] shapes = new int[edges.length];
        for (int s = 0; s < streets.length; s++) {
            int[] street = streets[s];
            edges[2 * s] = street;
            edges[2 * s + 1] = new int[] {street[1], street[0], street[2], street[3]};
            shapes[2 * s] = s;
            shapes[2 * s + 1] = ~s;
        }
        return build(vertexCount, edges, shapes);
    }

    private static RoadGraph build(int vertexCount, int[][] unsorted, int[] shapeOf) {
        Integer[] byOrigin = new Integer[unsorted.length];
        Arrays.setAll(byOrigin, i -> i);
        Arrays.sort(byOrigin, Comparator.comparingInt(i -> unsorted[i][0]));
        int[][] sorted = new int[unsorted.length][];
        int[] edgeShape = new int[unsorted.length];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = unsorted[byOrigin[i]];
            edgeShape[i] = shapeOf[byOrigin[i]];
        }
        Arrays.sort(sorted, Comparator.comparingInt(edge -> edge[0]));
        int[] firstEdge = new int[vertexCount + 1];
        for (int[] edge : sorted) {
            firstEdge[edge[0] + 1]++;
        }
        for (int v = 0; v < vertexCount; v++) {
            firstEdge[v + 1] += firstEdge[v];
        }
        int e = sorted.length;
        int[] target = new int[e];
        int[] meters = new int[e];
        int[] seconds = new int[e];
        int[] shape = new int[e];
        for (int i = 0; i < e; i++) {
            target[i] = sorted[i][1];
            meters[i] = sorted[i][2];
            seconds[i] = sorted[i][3];
            shape[i] = edgeShape[i];
        }
        int shapeCount = 0;
        for (int value : shapeOf) {
            shapeCount = Math.max(shapeCount, (value < 0 ? ~value : value) + 1);
        }
        long[] ids = new long[vertexCount];
        int[] lat = new int[vertexCount];
        int[] lon = new int[vertexCount];
        int[] firstShapePoint = new int[shapeCount + 1];
        int[] shapeLat = new int[2 * shapeCount];
        int[] shapeLon = new int[2 * shapeCount];
        for (int v = 0; v < vertexCount; v++) {
            ids[v] = 1000 + v;
            lat[v] = 47_000_000 + 10_000 * v;
            lon[v] = -740_000_000;
        }
        for (int s = 0; s < shapeCount; s++) {
            firstShapePoint[s + 1] = 2 * (s + 1);
        }
        for (int i = 0; i < e; i++) {
            if (shape[i] >= 0) {
                int s = shape[i];
                shapeLat[2 * s] = lat[sorted[i][0]];
                shapeLat[2 * s + 1] = lat[sorted[i][1]];
                shapeLon[2 * s] = lon[sorted[i][0]];
                shapeLon[2 * s + 1] = lon[sorted[i][1]];
            }
        }
        return new RoadGraph(ids, lat, lon, firstEdge, target, meters, seconds, new byte[e], shape, firstShapePoint,
                shapeLat, shapeLon, new boolean[shapeCount]);
    }
}
