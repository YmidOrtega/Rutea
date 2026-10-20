package me.ymid.rutea.solver;

import java.util.Objects;

public final class DistanceMatrix {

    private final int size;
    private final int[] distances;

    private DistanceMatrix(int size, int[] distances) {
        this.size = size;
        this.distances = distances;
    }

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

    /** Puede ser asimétrica: en una red vial los sentidos hacen que ir y volver cuesten distinto. */
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

    /** nint de TSPLIB, el redondeo de CVRPLIB: sin él los costos no coinciden con las BKS. */
    static int nint(double value) {
        return (int) (value + 0.5);
    }

    public int size() {
        return size;
    }

    public int get(int from, int to) {
        return distances[from * size + to];
    }

    public boolean isSymmetric() {
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                if (distances[i * size + j] != distances[j * size + i]) {
                    return false;
                }
            }
        }
        return true;
    }
}
