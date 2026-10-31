package me.ymid.rutea.geo;

import java.util.Arrays;

final class IntList {

    private int[] values;
    private int size;

    IntList(int capacity) {
        values = new int[Math.max(capacity, 4)];
    }

    void add(int value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, size * 2);
        }
        values[size++] = value;
    }

    int size() {
        return size;
    }

    int get(int index) {
        return values[index];
    }

    int[] toArray() {
        return Arrays.copyOf(values, size);
    }
}
