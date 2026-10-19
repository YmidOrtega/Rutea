package me.ymid.rutea.solver;

import java.util.Objects;

/**
 * Matriz de distancias enteras entre todos los nodos, guardada en un arreglo plano para que la
 * consulta sea una sola lectura de memoria.
 *
 * <p>Las distancias euclidianas se redondean con {@code nint} de TSPLIB, la misma convención de
 * CVRPLIB para el set X. Así los costos son exactos y comparables con las BKS publicadas.
 * Las distancias de una red vial llegan ya calculadas por {@link #of(int[][])} y pueden ser
 * asimétricas (sentidos viales).
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

    /**
     * Matriz a partir de distancias ya calculadas, por ejemplo sobre una red vial.
     *
     * @param distances matriz cuadrada; {@code distances[i][j]} es la distancia de {@code i} a
     *                  {@code j}, no negativa y cero en la diagonal
     */
    public static DistanceMatrix of(int[][] distances) {
        Objects.requireNonNull(distances, "distances");
        int n = distances.length;
        int[] flat = new int[Math.multiplyExact(n, n)];
        for (int i = 0; i < n; i++) {
            int[] row = Objects.requireNonNull(distances[i], "distances[" + i + "]");
            if (row.length != n) {
                throw new IllegalArgumentException("la fila " + i + " tiene " + row.length + " valores; se esperaban " + n);
            }
            for (int j = 0; j < n; j++) {
                if (row[j] < 0 || (i == j && row[j] != 0)) {
                    throw new IllegalArgumentException("distancia inválida de " + i + " a " + j + ": " + row[j]);
                }
                flat[i * n + j] = row[j];
            }
        }
        return new DistanceMatrix(n, flat);
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
