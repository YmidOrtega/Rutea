package me.ymid.rutea.geo;

import java.util.Arrays;
import java.util.Comparator;

/** Grafos chicos hechos a mano: cada arco es {origen, destino, metros, segundos}. */
final class TestGraphs {

    private TestGraphs() {
    }

    static RoadGraph of(int vertexCount, int[]... edges) {
        int[][] sorted = edges.clone();
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
            shape[i] = i;
        }
        long[] ids = new long[vertexCount];
        int[] lat = new int[vertexCount];
        int[] lon = new int[vertexCount];
        int[] firstShapePoint = new int[e + 1];
        int[] shapeLat = new int[2 * e];
        int[] shapeLon = new int[2 * e];
        for (int v = 0; v < vertexCount; v++) {
            ids[v] = 1000 + v;
            lat[v] = 47_000_000 + 10_000 * v;
            lon[v] = -740_000_000;
        }
        for (int i = 0; i < e; i++) {
            firstShapePoint[i + 1] = 2 * (i + 1);
            shapeLat[2 * i] = lat[sorted[i][0]];
            shapeLat[2 * i + 1] = lat[sorted[i][1]];
            shapeLon[2 * i] = lon[sorted[i][0]];
            shapeLon[2 * i + 1] = lon[sorted[i][1]];
        }
        return new RoadGraph(ids, lat, lon, firstEdge, target, meters, seconds, new byte[e], shape, firstShapePoint,
                shapeLat, shapeLon, new boolean[e]);
    }
}
