package me.ymid.rutea.geo.osm;

import java.util.Arrays;

final class LongList {

    private long[] values;
    private int size;

    LongList(int capacity) {
        values = new long[Math.max(capacity, 4)];
    }

    void add(long value) {
        if (size == values.length) {
            values = Arrays.copyOf(values, size * 2);
        }
        values[size++] = value;
    }

    int size() {
        return size;
    }

    long get(int index) {
        return values[index];
    }

    long[] toArray() {
        return Arrays.copyOf(values, size);
    }

    /** Los ids, coordenadas y referencias de OSM vienen como diferencias con el valor anterior. */
    long[] toDeltaDecodedArray() {
        long[] decoded = new long[size];
        long running = 0;
        for (int i = 0; i < size; i++) {
            running += values[i];
            decoded[i] = running;
        }
        return decoded;
    }
}
