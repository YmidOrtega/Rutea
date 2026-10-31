package me.ymid.rutea.geo;

import java.util.Arrays;

/**
 * Componentes fuertemente conexas con el algoritmo de Tarjan, sin recursión: una ciudad tiene cadenas
 * de cientos de miles de vértices y la pila de llamadas no alcanza.
 */
final class StrongComponents {

    private StrongComponents() {
    }

    /** Vértices de la mayor componente, en orden creciente. Si hay empate, gana la que tiene el vértice menor. */
    static int[] largest(int vertexCount, int[] firstEdge, int[] targets) {
        int[] component = label(vertexCount, firstEdge, targets);
        int[] size = new int[vertexCount];
        int[] smallestVertex = new int[vertexCount];
        Arrays.fill(smallestVertex, Integer.MAX_VALUE);
        for (int v = 0; v < vertexCount; v++) {
            size[component[v]]++;
            smallestVertex[component[v]] = Math.min(smallestVertex[component[v]], v);
        }
        int best = -1;
        for (int c = 0; c < vertexCount; c++) {
            if (size[c] > 0 && (best < 0 || size[c] > size[best]
                    || (size[c] == size[best] && smallestVertex[c] < smallestVertex[best]))) {
                best = c;
            }
        }
        if (best < 0) {
            return new int[0];
        }
        int[] vertices = new int[size[best]];
        int count = 0;
        for (int v = 0; v < vertexCount; v++) {
            if (component[v] == best) {
                vertices[count++] = v;
            }
        }
        return vertices;
    }

    static int[] label(int vertexCount, int[] firstEdge, int[] targets) {
        int[] index = new int[vertexCount];
        int[] low = new int[vertexCount];
        int[] component = new int[vertexCount];
        boolean[] onStack = new boolean[vertexCount];
        Arrays.fill(index, -1);
        int[] stack = new int[vertexCount];
        int stackSize = 0;
        int[] callVertex = new int[vertexCount];
        int[] callEdge = new int[vertexCount];
        int nextIndex = 0;
        int components = 0;
        for (int root = 0; root < vertexCount; root++) {
            if (index[root] >= 0) {
                continue;
            }
            int depth = 0;
            callVertex[0] = root;
            callEdge[0] = firstEdge[root];
            index[root] = low[root] = nextIndex++;
            stack[stackSize++] = root;
            onStack[root] = true;
            while (depth >= 0) {
                int v = callVertex[depth];
                if (callEdge[depth] < firstEdge[v + 1]) {
                    int w = targets[callEdge[depth]++];
                    if (index[w] < 0) {
                        index[w] = low[w] = nextIndex++;
                        stack[stackSize++] = w;
                        onStack[w] = true;
                        depth++;
                        callVertex[depth] = w;
                        callEdge[depth] = firstEdge[w];
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                    continue;
                }
                if (low[v] == index[v]) {
                    int w;
                    do {
                        w = stack[--stackSize];
                        onStack[w] = false;
                        component[w] = components;
                    } while (w != v);
                    components++;
                }
                depth--;
                if (depth >= 0) {
                    int parent = callVertex[depth];
                    low[parent] = Math.min(low[parent], low[v]);
                }
            }
        }
        return component;
    }
}
