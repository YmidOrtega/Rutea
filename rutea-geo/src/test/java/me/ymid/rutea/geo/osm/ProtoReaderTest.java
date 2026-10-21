package me.ymid.rutea.geo.osm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProtoReaderTest {

    @Test
    void decodesVarintsUpToTenBytes() throws PbfFormatException {
        ProtoReader reader = new ProtoReader(new ProtoWriter()
                .varint(1, 0).varint(2, 300).varint(3, -1).varint(4, Long.MAX_VALUE).toByteArray());

        long[] values = new long[4];
        for (int i = 0; i < 4; i++) {
            assertThat(reader.next()).isTrue();
            assertThat(reader.field()).isEqualTo(i + 1);
            values[i] = reader.varint();
        }

        assertThat(values).containsExactly(0, 300, -1, Long.MAX_VALUE);
        assertThat(reader.next()).isFalse();
    }

    @Test
    void decodesZigzagSignedValues() throws PbfFormatException {
        ProtoReader reader = new ProtoReader(new ProtoWriter()
                .sint64(1, -1).sint64(1, 1).sint64(1, Long.MIN_VALUE).sint64(1, Long.MAX_VALUE).toByteArray());

        long[] values = new long[4];
        for (int i = 0; i < 4; i++) {
            reader.next();
            values[i] = reader.sint64();
        }

        assertThat(values).containsExactly(-1, 1, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    @Test
    void readsPackedAndUnpackedRepeatedFieldsTheSame() throws PbfFormatException {
        byte[] packed = new ProtoWriter().packedSint64(1, -5, 0, 7).toByteArray();
        byte[] unpacked = new ProtoWriter().sint64(1, -5).sint64(1, 0).sint64(1, 7).toByteArray();

        assertThat(readRepeated(packed)).containsExactly(-5, 0, 7);
        assertThat(readRepeated(unpacked)).containsExactly(-5, 0, 7);
    }

    @Test
    void readsNestedMessagesWithoutLosingThePosition() throws PbfFormatException {
        ProtoWriter inner = new ProtoWriter().string(1, "highway").varint(2, 42);
        ProtoReader outer = new ProtoReader(new ProtoWriter().message(1, inner).varint(2, 7).toByteArray());

        outer.next();
        ProtoReader nested = outer.message();
        nested.next();
        assertThat(nested.string()).isEqualTo("highway");
        nested.next();
        assertThat(nested.varint()).isEqualTo(42);
        assertThat(nested.next()).isFalse();

        outer.next();
        assertThat(outer.varint()).isEqualTo(7);
    }

    @Test
    void skipsEveryWireTypeItKnows() throws PbfFormatException {
        ProtoReader reader = new ProtoReader(new ProtoWriter()
                .varint(1, 99)
                .raw(2 << 3 | ProtoReader.FIXED64, 1, 2, 3, 4, 5, 6, 7, 8)
                .string(3, "ignorado")
                .raw(4 << 3 | ProtoReader.FIXED32, 1, 2, 3, 4)
                .varint(5, 11)
                .toByteArray());

        for (int i = 0; i < 4; i++) {
            reader.next();
            reader.skip();
        }
        reader.next();

        assertThat(reader.field()).isEqualTo(5);
        assertThat(reader.varint()).isEqualTo(11);
    }

    @Test
    void rejectsVarintCutAtTheEnd() throws PbfFormatException {
        ProtoReader reader = new ProtoReader(new byte[] {1 << 3, (byte) 0x80});
        reader.next();

        assertThatThrownBy(reader::varint).isInstanceOf(PbfFormatException.class).hasMessageContaining("cortado");
    }

    @Test
    void rejectsVarintLongerThanTenBytes() throws PbfFormatException {
        byte[] bytes = new byte[12];
        bytes[0] = 1 << 3;
        for (int i = 1; i < bytes.length; i++) {
            bytes[i] = (byte) 0x80;
        }
        ProtoReader reader = new ProtoReader(bytes);
        reader.next();

        assertThatThrownBy(reader::varint).isInstanceOf(PbfFormatException.class).hasMessageContaining("10 bytes");
    }

    @Test
    void rejectsLengthPastTheEndOfTheMessage() throws PbfFormatException {
        ProtoReader reader = new ProtoReader(new byte[] {1 << 3 | ProtoReader.LENGTH_DELIMITED, 50, 'a'});
        reader.next();

        assertThatThrownBy(reader::string).isInstanceOf(PbfFormatException.class)
                .hasMessageContaining("pasa el final");
    }

    @Test
    void rejectsFieldReadWithTheWrongWireType() throws PbfFormatException {
        ProtoReader reader = new ProtoReader(new ProtoWriter().string(1, "texto").toByteArray());
        reader.next();

        assertThatThrownBy(reader::varint).isInstanceOf(PbfFormatException.class)
                .hasMessage("campo 1: tipo 2, se esperaba 0");
    }

    @Test
    void rejectsFieldNumberZero() {
        ProtoReader reader = new ProtoReader(new byte[] {0, 1});

        assertThatThrownBy(reader::next).isInstanceOf(PbfFormatException.class)
                .hasMessageContaining("etiqueta de campo inválida");
    }

    private static long[] readRepeated(byte[] bytes) throws PbfFormatException {
        ProtoReader reader = new ProtoReader(bytes);
        LongList values = new LongList(4);
        while (reader.next()) {
            reader.repeatedVarints(values, true);
        }
        return values.toArray();
    }
}
