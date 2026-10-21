package me.ymid.rutea.solver;

import java.util.Objects;

public final class Instance {

    private final String name;
    private final int capacity;
    private final int[] demands;
    private final DistanceMatrix distances;

    public Instance(String name, int capacity, int[] demands, DistanceMatrix distances) {
        this.name = Objects.requireNonNull(name, "name");
        Objects.requireNonNull(demands, "demands");
        this.distances = Objects.requireNonNull(distances, "distances");
        if (capacity <= 0) {
            throw new IllegalArgumentException(name + ": la capacidad debe ser positiva, es " + capacity);
        }
        if (demands.length != distances.size()) {
            throw new IllegalArgumentException(name + ": hay " + demands.length
                    + " demandas para " + distances.size() + " nodos");
        }
        if (demands.length < 2) {
            throw new IllegalArgumentException(name + ": se necesita un depósito y al menos un cliente");
        }
        if (demands[0] != 0) {
            throw new IllegalArgumentException(name + ": el depósito no puede tener demanda, tiene " + demands[0]);
        }
        for (int i = 1; i < demands.length; i++) {
            if (demands[i] < 0 || demands[i] > capacity) {
                throw new IllegalArgumentException(name + ": la demanda del cliente " + i + " es " + demands[i]
                        + ", fuera de [0, " + capacity + "]");
            }
        }
        this.capacity = capacity;
        this.demands = demands.clone();
    }

    public String name() {
        return name;
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return demands.length;
    }

    public int customerCount() {
        return demands.length - 1;
    }

    public int demand(int node) {
        return demands[node];
    }

    public int distance(int from, int to) {
        return distances.get(from, to);
    }

    public DistanceMatrix distances() {
        return distances;
    }

    @Override
    public String toString() {
        return name + " (" + customerCount() + " clientes, capacidad " + capacity + ")";
    }
}
