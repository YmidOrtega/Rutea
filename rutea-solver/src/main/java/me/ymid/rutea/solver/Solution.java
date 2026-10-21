package me.ymid.rutea.solver;

import java.util.Arrays;
import java.util.Objects;

public final class Solution {

    private final int[][] routes;

    public Solution(int[][] routes) {
        Objects.requireNonNull(routes, "routes");
        this.routes = new int[routes.length][];
        for (int k = 0; k < routes.length; k++) {
            this.routes[k] = Objects.requireNonNull(routes[k], "routes[" + k + "]").clone();
        }
    }

    public static Solution of(int[]... routes) {
        return new Solution(routes);
    }

    public int routeCount() {
        return routes.length;
    }

    public int routeLength(int route) {
        return routes[route].length;
    }

    public int customer(int route, int position) {
        return routes[route][position];
    }

    public int[] route(int route) {
        return routes[route].clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Solution that && Arrays.deepEquals(routes, that.routes);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(routes);
    }

    @Override
    public String toString() {
        return Arrays.deepToString(routes);
    }
}
