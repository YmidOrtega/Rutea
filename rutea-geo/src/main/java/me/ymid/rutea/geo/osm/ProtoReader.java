package me.ymid.rutea.geo.osm;

import java.nio.charset.StandardCharsets;

/**
 * Lector del formato binario de protobuf: solo lo que usa el PBF de OSM. Cada mensaje anidado es un
 * {@code ProtoReader} sobre el mismo arreglo, sin copiar bytes.
 */
final class ProtoReader {

    static final int VARINT = 0;
    static final int FIXED64 = 1;
    static final int LENGTH_DELIMITED = 2;
    static final int FIXED32 = 5;

    private final byte[] buffer;
    private final int limit;
    private int position;
    private int tag;

    ProtoReader(byte[] buffer) {
        this(buffer, 0, buffer.length);
    }

    private ProtoReader(byte[] buffer, int offset, int limit) {
        this.buffer = buffer;
        this.position = offset;
        this.limit = limit;
    }

    /** Avanza al siguiente campo; devuelve {@code false} al final del mensaje. */
    boolean next() throws PbfFormatException {
        if (position >= limit) {
            return false;
        }
        long value = varintBefore(limit);
        if (value >>> 32 != 0 || (value >>> 3) == 0) {
            throw new PbfFormatException("etiqueta de campo inválida: " + value);
        }
        tag = (int) value;
        return true;
    }

    int field() {
        return tag >>> 3;
    }

    int wireType() {
        return tag & 7;
    }

    long varint() throws PbfFormatException {
        requireWireType(VARINT);
        return varintBefore(limit);
    }

    long sint64() throws PbfFormatException {
        return zigzag(varint());
    }

    static long zigzag(long encoded) {
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    String string() throws PbfFormatException {
        int length = length();
        String value = new String(buffer, position, length, StandardCharsets.UTF_8);
        position += length;
        return value;
    }

    byte[] bytes() throws PbfFormatException {
        int length = length();
        byte[] value = new byte[length];
        System.arraycopy(buffer, position, value, 0, length);
        position += length;
        return value;
    }

    ProtoReader message() throws PbfFormatException {
        int length = length();
        ProtoReader nested = new ProtoReader(buffer, position, position + length);
        position += length;
        return nested;
    }

    /**
     * Agrega un campo repetido de varints a {@code into}. Acepta las dos codificaciones válidas en
     * proto2: empaquetada (un solo bloque con longitud) o un varint por aparición del campo.
     */
    void repeatedVarints(LongList into, boolean zigzag) throws PbfFormatException {
        if (wireType() == VARINT) {
            long value = varintBefore(limit);
            into.add(zigzag ? zigzag(value) : value);
            return;
        }
        int length = length();
        int end = position + length;
        while (position < end) {
            long value = varintBefore(end);
            into.add(zigzag ? zigzag(value) : value);
        }
    }

    private long varintBefore(int end) throws PbfFormatException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (position >= end) {
                throw new PbfFormatException("varint cortado al final del campo o del mensaje");
            }
            byte b = buffer[position++];
            result |= (long) (b & 0x7F) << shift;
            if (b >= 0) {
                return result;
            }
        }
        throw new PbfFormatException("varint de más de 10 bytes");
    }

    private void requireWireType(int expected) throws PbfFormatException {
        if (wireType() != expected) {
            throw new PbfFormatException("campo " + field() + ": tipo " + wireType()
                    + ", se esperaba " + expected);
        }
    }

    void skip() throws PbfFormatException {
        switch (wireType()) {
            case VARINT -> varintBefore(limit);
            case FIXED64 -> advance(8);
            case LENGTH_DELIMITED -> advance(length());
            case FIXED32 -> advance(4);
            default -> throw new PbfFormatException("campo " + field() + ": tipo de cable "
                    + wireType() + " no soportado");
        }
    }

    private int length() throws PbfFormatException {
        requireWireType(LENGTH_DELIMITED);
        long length = varintBefore(limit);
        if (length < 0 || length > limit - position) {
            throw new PbfFormatException("campo " + field() + ": longitud " + length
                    + " pasa el final del mensaje");
        }
        return (int) length;
    }

    private void advance(int count) throws PbfFormatException {
        if (count > limit - position) {
            throw new PbfFormatException("campo " + field() + " pasa el final del mensaje");
        }
        position += count;
    }
}
