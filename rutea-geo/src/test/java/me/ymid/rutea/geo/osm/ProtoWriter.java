package me.ymid.rutea.geo.osm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Codificador protobuf mínimo para armar archivos PBF de prueba sin herramientas externas. */
final class ProtoWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    ProtoWriter varint(int field, long value) {
        tag(field, ProtoReader.VARINT);
        rawVarint(value);
        return this;
    }

    ProtoWriter sint64(int field, long value) {
        return varint(field, zigzag(value));
    }

    ProtoWriter bytes(int field, byte[] value) {
        tag(field, ProtoReader.LENGTH_DELIMITED);
        rawVarint(value.length);
        out.writeBytes(value);
        return this;
    }

    ProtoWriter string(int field, String value) {
        return bytes(field, value.getBytes(StandardCharsets.UTF_8));
    }

    ProtoWriter message(int field, ProtoWriter message) {
        return bytes(field, message.toByteArray());
    }

    ProtoWriter packed(int field, long... values) {
        ProtoWriter packed = new ProtoWriter();
        for (long value : values) {
            packed.rawVarint(value);
        }
        return bytes(field, packed.toByteArray());
    }

    ProtoWriter packedSint64(int field, long... values) {
        long[] encoded = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            encoded[i] = zigzag(values[i]);
        }
        return packed(field, encoded);
    }

    /** Codifica con diferencias, como hace OSM con ids, coordenadas y referencias. */
    ProtoWriter packedDeltaSint64(int field, long... values) {
        long[] deltas = new long[values.length];
        long previous = 0;
        for (int i = 0; i < values.length; i++) {
            deltas[i] = values[i] - previous;
            previous = values[i];
        }
        return packedSint64(field, deltas);
    }

    ProtoWriter raw(int... bytes) {
        for (int b : bytes) {
            out.write(b);
        }
        return this;
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    private void tag(int field, int wireType) {
        rawVarint((long) field << 3 | wireType);
    }

    private void rawVarint(long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static long zigzag(long value) {
        return (value << 1) ^ (value >> 63);
    }
}
