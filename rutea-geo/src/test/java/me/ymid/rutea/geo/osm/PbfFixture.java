package me.ymid.rutea.geo.osm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/** Arma archivos PBF de prueba, bloque por bloque, con la misma estructura que publica Geofabrik. */
public final class PbfFixture {

    public enum Compression { RAW, ZLIB }

    private final ByteArrayOutputStream file = new ByteArrayOutputStream();

    public PbfFixture header(String... requiredFeatures) {
        ProtoWriter header = new ProtoWriter();
        for (String feature : requiredFeatures) {
            header.string(4, feature);
        }
        return blob("OSMHeader", blobMessage(header.toByteArray(), Compression.ZLIB));
    }

    public PbfFixture standardHeader() {
        return header("OsmSchema-V0.6", "DenseNodes");
    }

    public PbfFixture data(Block block, Compression compression) {
        return blob("OSMData", blobMessage(block.toByteArray(), compression));
    }

    PbfFixture blob(String type, byte[] blob) {
        byte[] header = new ProtoWriter().string(1, type).varint(3, blob.length).toByteArray();
        file.write(header.length >>> 24);
        file.write(header.length >>> 16);
        file.write(header.length >>> 8);
        file.write(header.length);
        file.writeBytes(header);
        file.writeBytes(blob);
        return this;
    }

    PbfFixture rawBytes(byte... bytes) {
        file.writeBytes(bytes);
        return this;
    }

    byte[] toByteArray() {
        return file.toByteArray();
    }

    public Path write(Path dir) throws IOException {
        return Files.write(dir.resolve("fixture.osm.pbf"), toByteArray());
    }

    static byte[] blobMessage(byte[] payload, Compression compression) {
        if (compression == Compression.RAW) {
            return new ProtoWriter().bytes(1, payload).toByteArray();
        }
        Deflater deflater = new Deflater();
        deflater.setInput(payload);
        deflater.finish();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        while (!deflater.finished()) {
            compressed.write(chunk, 0, deflater.deflate(chunk));
        }
        deflater.end();
        return new ProtoWriter().varint(2, payload.length).bytes(3, compressed.toByteArray()).toByteArray();
    }

    /** Un PrimitiveBlock; cada llamada agrega un PrimitiveGroup con un solo tipo de elemento. */
    public static final class Block {

        private final List<String> strings = new ArrayList<>(List.of(""));
        private final List<ProtoWriter> groups = new ArrayList<>();
        private long granularity = 100;
        private long latOffset;
        private long lonOffset;

        Block coordinates(long granularity, long latOffset, long lonOffset) {
            this.granularity = granularity;
            this.latOffset = latOffset;
            this.lonOffset = lonOffset;
            return this;
        }

        /** Coordenadas en nanogrados, ya ajustadas a la granularidad del bloque. */
        public Block denseNodes(long[] ids, long[] latNano, long[] lonNano) {
            ProtoWriter dense = new ProtoWriter()
                    .packedDeltaSint64(1, ids)
                    .packedDeltaSint64(8, scaled(latNano, latOffset))
                    .packedDeltaSint64(9, scaled(lonNano, lonOffset));
            groups.add(new ProtoWriter().message(2, dense));
            return this;
        }

        /** Como {@link #denseNodes}, con las etiquetas de cada nodo en {@code keys_vals} (vacío si no tiene). */
        public Block taggedDenseNodes(long[] ids, long[] latNano, long[] lonNano, String[]... tagsPerNode) {
            LongList keysAndValues = new LongList(16);
            for (String[] tags : tagsPerNode) {
                for (String tag : tags) {
                    keysAndValues.add(index(tag));
                }
                keysAndValues.add(0);
            }
            ProtoWriter dense = new ProtoWriter()
                    .packedDeltaSint64(1, ids)
                    .packedDeltaSint64(8, scaled(latNano, latOffset))
                    .packedDeltaSint64(9, scaled(lonNano, lonOffset))
                    .packed(10, keysAndValues.toArray());
            groups.add(new ProtoWriter().message(2, dense));
            return this;
        }

        Block node(long id, long latNano, long lonNano, String... keysAndValues) {
            ProtoWriter node = new ProtoWriter().sint64(1, id);
            tags(node, keysAndValues);
            node.sint64(8, (latNano - latOffset) / granularity)
                    .sint64(9, (lonNano - lonOffset) / granularity);
            groups.add(new ProtoWriter().message(1, node));
            return this;
        }

        public Block way(long id, long[] refs, String... keysAndValues) {
            ProtoWriter way = new ProtoWriter().varint(1, id);
            tags(way, keysAndValues);
            way.packedDeltaSint64(8, refs);
            groups.add(new ProtoWriter().message(3, way));
            return this;
        }

        public Block relation(long id, int[] types, long[] refs, String[] roles, String... keysAndValues) {
            ProtoWriter relation = new ProtoWriter().varint(1, id);
            tags(relation, keysAndValues);
            long[] roleIds = new long[roles.length];
            for (int i = 0; i < roles.length; i++) {
                roleIds[i] = index(roles[i]);
            }
            long[] typeCodes = new long[types.length];
            for (int i = 0; i < types.length; i++) {
                typeCodes[i] = types[i];
            }
            relation.packed(8, roleIds).packedDeltaSint64(9, refs).packed(10, typeCodes);
            groups.add(new ProtoWriter().message(4, relation));
            return this;
        }

        Block group(ProtoWriter group) {
            groups.add(group);
            return this;
        }

        int index(String string) {
            int index = strings.indexOf(string);
            if (index >= 0) {
                return index;
            }
            strings.add(string);
            return strings.size() - 1;
        }

        byte[] toByteArray() {
            ProtoWriter table = new ProtoWriter();
            for (String string : strings) {
                table.bytes(1, string.getBytes(StandardCharsets.UTF_8));
            }
            ProtoWriter block = new ProtoWriter().message(1, table);
            for (ProtoWriter group : groups) {
                block.message(2, group);
            }
            if (granularity != 100) {
                block.varint(17, granularity);
            }
            if (latOffset != 0) {
                block.varint(19, latOffset);
            }
            if (lonOffset != 0) {
                block.varint(20, lonOffset);
            }
            return block.toByteArray();
        }

        private void tags(ProtoWriter element, String... keysAndValues) {
            long[] keys = new long[keysAndValues.length / 2];
            long[] values = new long[keysAndValues.length / 2];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = index(keysAndValues[2 * i]);
                values[i] = index(keysAndValues[2 * i + 1]);
            }
            if (keys.length > 0) {
                element.packed(2, keys).packed(3, values);
            }
        }

        private long[] scaled(long[] nanodegrees, long offset) {
            long[] scaled = new long[nanodegrees.length];
            for (int i = 0; i < nanodegrees.length; i++) {
                scaled[i] = (nanodegrees[i] - offset) / granularity;
            }
            return scaled;
        }
    }
}
