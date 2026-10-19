package me.ymid.rutea.solver;

import java.util.Objects;

/**
 * Matriz de distancias enteras y simétricas entre todos los nodos, guardada en un arreglo plano
 * para que la consulta sea una sola lectura de memoria.
 *
 * <p>Las distancias euclidianas se redondean con {@code nint} de TSPLIB, la misma convención de
 * CVRPLIB para el set X. Así los costos son exactos y comparables con las BKS publicadas.
 */
public final class DistanceMatrix {

    private final int size;
    private final int[] distances;

    private DistanceMatrix(int size, int[] distances) {
        this.size = size;
        this.distances = distances;
    }

    /**
     * Construye la matriz a partir de coordenadas planas ({@code EUC_2D} en TSPLIB).
     *
     * @param x abscisas, una por nodo; el nodo {@code 0} es el depósito
     * @param y ordenadas, en el mismo orden que {@code x}
     */
    public static DistanceMatrix euclidean(double[] x, double[] y) {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        if (x.length != y.length) {
            throw new IllegalArgumentException(
                    "x tiene " + x.length + " coordenadas y y tiene " + y.length);
        }
        int n = x.length;
        int[] distances = new int[Math.multiplyExact(n, n)];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = x[i] - x[j];
                double dy = y[i] - y[j];
                int d = nint(Math.sqrt(dx * dx + dy * dy));
                distances[i * n + j] = d;
                distances[j * n + i] = d;
            }
        }
        return new DistanceMatrix(n, distances);
    }

    /** Redondeo {@code nint} de TSPLIB: {@code (int) (x + 0.5)}, definido para distancias no negativas. */
    static int nint(double value) {
        return (int) (value + 0.5);
    }

    /** Número de nodos, depósito incluido. */
    public int size() {
        return size;
    }

    public int get(int from, int to) {
        return distances[from * size + to];
    }
}
