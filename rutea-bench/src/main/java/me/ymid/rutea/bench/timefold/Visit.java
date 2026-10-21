package me.ymid.rutea.bench.timefold;

public final class Visit {

    private final int node;
    private final int demand;

    public Visit(int node, int demand) {
        this.node = node;
        this.demand = demand;
    }

    public int node() {
        return node;
    }

    public int demand() {
        return demand;
    }

    @Override
    public String toString() {
        return "Visit " + node;
    }
}
