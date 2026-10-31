package me.ymid.rutea.geo.osm;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import me.ymid.rutea.geo.osm.OsmRelation.Member;
import me.ymid.rutea.geo.osm.OsmRelation.MemberType;

/**
 * Lector de archivos {@code .osm.pbf} (formato descrito en https://wiki.openstreetmap.org/wiki/PBF_Format).
 *
 * <p>Los bloques se descomprimen y decodifican en paralelo, pero se entregan en el orden del archivo
 * y siempre desde el hilo que llama: el consumidor no necesita ser seguro entre hilos y dos lecturas
 * del mismo archivo producen exactamente la misma secuencia.
 */
public final class PbfReader {

    /**
     * Qué decodificar. {@code NODES} da la posición de todos los nodos; {@code TAGGED_NODES}, solo los que
     * tienen etiquetas, con ellas.
     */
    public enum Element { NODES, TAGGED_NODES, WAYS, RELATIONS }

    /** Límites del formato: un archivo que los pasa está dañado o no es un PBF. */
    private static final int MAX_BLOB_HEADER_SIZE = 64 * 1024;
    private static final int MAX_BLOB_SIZE = 32 * 1024 * 1024;

    private static final Set<String> SUPPORTED_FEATURES = Set.of("OsmSchema-V0.6", "DenseNodes");

    private PbfReader() {
    }

    public static void read(Path file, Set<Element> elements, Consumer<OsmBlock> sink) throws IOException {
        read(file, elements, Runtime.getRuntime().availableProcessors(), sink);
    }

    public static void read(Path file, Set<Element> elements, int threads, Consumer<OsmBlock> sink)
            throws IOException {
        if (threads < 1) {
            throw new IllegalArgumentException("threads debe ser al menos 1: " + threads);
        }
        Set<Element> wanted = elements.isEmpty() ? EnumSet.noneOf(Element.class) : EnumSet.copyOf(elements);
        String name = file.getFileName().toString();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            DataInputStream data = new DataInputStream(in);
            ArrayDeque<Future<OsmBlock>> pending = new ArrayDeque<>();
            boolean headerSeen = false;
            for (int blobIndex = 0; ; blobIndex++) {
                String where = name + ": bloque " + blobIndex;
                int headerSize = readBlobHeaderSize(data, where);
                if (headerSize < 0) {
                    break;
                }
                byte[] headerBytes = readFully(data, headerSize, where);
                BlobHeader header = inBlock(where, () -> BlobHeader.parse(headerBytes));
                byte[] blob = readFully(data, header.dataSize(), where);
                switch (header.type()) {
                    case "OSMHeader" -> {
                        if (headerSeen) {
                            throw new PbfFormatException(where + ": OSMHeader repetido");
                        }
                        inBlock(where, () -> checkRequiredFeatures(uncompress(blob)));
                        headerSeen = true;
                    }
                    case "OSMData" -> {
                        if (!headerSeen) {
                            throw new PbfFormatException(where + ": OSMData antes de OSMHeader");
                        }
                        pending.add(pool.submit(() -> inBlock(where, () -> decodeBlock(uncompress(blob), wanted))));
                        if (pending.size() >= 2 * threads) {
                            sink.accept(await(pending.poll()));
                        }
                    }
                    default -> {
                        // El formato pide ignorar los tipos de bloque desconocidos.
                    }
                }
            }
            while (!pending.isEmpty()) {
                sink.accept(await(pending.poll()));
            }
            if (!headerSeen) {
                throw new PbfFormatException(name + ": el archivo no tiene OSMHeader");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private interface Decoding<T> {
        T run() throws PbfFormatException;
    }

    private static <T> T inBlock(String where, Decoding<T> decoding) throws PbfFormatException {
        try {
            return decoding.run();
        } catch (PbfFormatException e) {
            throw new PbfFormatException(where + ": " + e.getMessage());
        }
    }

    private static OsmBlock await(Future<OsmBlock> block) throws IOException {
        try {
            return block.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("lectura del PBF interrumpida");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Devuelve -1 si el archivo terminó justo antes de un bloque, que es el final normal. */
    private static int readBlobHeaderSize(DataInputStream data, String where) throws IOException {
        int first = data.read();
        if (first < 0) {
            return -1;
        }
        int size;
        try {
            size = first << 24 | data.readUnsignedByte() << 16 | data.readUnsignedByte() << 8
                    | data.readUnsignedByte();
        } catch (EOFException e) {
            throw new PbfFormatException(where + ": archivo cortado en la longitud del encabezado");
        }
        if (size <= 0 || size > MAX_BLOB_HEADER_SIZE) {
            throw new PbfFormatException(where + ": encabezado de " + size + " bytes, el máximo es "
                    + MAX_BLOB_HEADER_SIZE);
        }
        return size;
    }

    private static byte[] readFully(DataInputStream data, int size, String where) throws IOException {
        byte[] bytes = data.readNBytes(size);
        if (bytes.length != size) {
            throw new PbfFormatException(where + ": archivo cortado; faltan " + (size - bytes.length)
                    + " de " + size + " bytes");
        }
        return bytes;
    }

    private record BlobHeader(String type, int dataSize) {

        static BlobHeader parse(byte[] bytes) throws PbfFormatException {
            ProtoReader reader = new ProtoReader(bytes);
            String type = null;
            long dataSize = -1;
            while (reader.next()) {
                switch (reader.field()) {
                    case 1 -> type = reader.string();
                    case 3 -> dataSize = reader.varint();
                    default -> reader.skip();
                }
            }
            if (type == null || dataSize < 0) {
                throw new PbfFormatException("al encabezado le falta el tipo o el tamaño");
            }
            if (dataSize > MAX_BLOB_SIZE) {
                throw new PbfFormatException("bloque de " + dataSize + " bytes, el máximo es "
                        + MAX_BLOB_SIZE);
            }
            return new BlobHeader(type, (int) dataSize);
        }
    }

    private static byte[] uncompress(byte[] blob) throws PbfFormatException {
        ProtoReader reader = new ProtoReader(blob);
        byte[] raw = null;
        byte[] zlib = null;
        long rawSize = -1;
        while (reader.next()) {
            switch (reader.field()) {
                case 1 -> raw = reader.bytes();
                case 2 -> rawSize = reader.varint();
                case 3 -> zlib = reader.bytes();
                case 4 -> throw unsupportedCompression("LZMA");
                case 5 -> throw unsupportedCompression("bzip2");
                case 6 -> throw unsupportedCompression("LZ4");
                case 7 -> throw unsupportedCompression("Zstandard");
                default -> reader.skip();
            }
        }
        if (raw != null) {
            return raw;
        }
        if (zlib == null) {
            throw new PbfFormatException("bloque sin datos");
        }
        if (rawSize < 0 || rawSize > MAX_BLOB_SIZE) {
            throw new PbfFormatException("tamaño descomprimido inválido: " + rawSize);
        }
        return inflate(zlib, (int) rawSize);
    }

    private static PbfFormatException unsupportedCompression(String format) {
        return new PbfFormatException("compresión " + format
                + " no soportada; solo zlib o sin comprimir, como publica Geofabrik");
    }

    private static byte[] inflate(byte[] zlib, int rawSize) throws PbfFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(zlib);
            byte[] out = new byte[rawSize];
            int filled = 0;
            while (filled < rawSize) {
                int count = inflater.inflate(out, filled, rawSize - filled);
                if (count == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    throw new PbfFormatException("zlib dio " + filled + " bytes, se esperaban "
                            + rawSize);
                }
                filled += count;
            }
            return out;
        } catch (DataFormatException e) {
            throw new PbfFormatException("zlib inválido: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    private static Void checkRequiredFeatures(byte[] headerBlock) throws PbfFormatException {
        ProtoReader reader = new ProtoReader(headerBlock);
        Set<String> unsupported = new TreeSet<>();
        while (reader.next()) {
            if (reader.field() == 4) {
                String feature = reader.string();
                if (!SUPPORTED_FEATURES.contains(feature)) {
                    unsupported.add(feature);
                }
            } else {
                reader.skip();
            }
        }
        if (!unsupported.isEmpty()) {
            throw new PbfFormatException("el archivo exige características no soportadas: "
                    + unsupported);
        }
        return null;
    }

    static OsmBlock decodeBlock(byte[] primitiveBlock, Set<Element> wanted)
            throws PbfFormatException {
        ProtoReader reader = new ProtoReader(primitiveBlock);
        String[] strings = new String[0];
        List<ProtoReader> groups = new ArrayList<>();
        long granularity = 100;
        long latOffset = 0;
        long lonOffset = 0;
        // La granularidad y los desplazamientos van al final del mensaje: los grupos se leen después.
        while (reader.next()) {
            switch (reader.field()) {
                case 1 -> strings = stringTable(reader.message());
                case 2 -> groups.add(reader.message());
                case 17 -> granularity = reader.varint();
                case 19 -> latOffset = reader.varint();
                case 20 -> lonOffset = reader.varint();
                default -> reader.skip();
            }
        }
        if (granularity <= 0) {
            throw new PbfFormatException("granularidad inválida: " + granularity);
        }
        boolean positions = wanted.contains(Element.NODES);
        boolean tagged = wanted.contains(Element.TAGGED_NODES);
        BlockBuilder block = new BlockBuilder(strings, granularity, latOffset, lonOffset);
        for (ProtoReader group : groups) {
            while (group.next()) {
                switch (group.field()) {
                    case 1 -> {
                        if (positions || tagged) {
                            block.node(group.message(), positions, tagged);
                        } else {
                            group.skip();
                        }
                    }
                    case 2 -> {
                        if (positions || tagged) {
                            block.denseNodes(group.message(), positions, tagged);
                        } else {
                            group.skip();
                        }
                    }
                    case 3 -> {
                        if (wanted.contains(Element.WAYS)) {
                            block.way(group.message());
                        } else {
                            group.skip();
                        }
                    }
                    case 4 -> {
                        if (wanted.contains(Element.RELATIONS)) {
                            block.relation(group.message());
                        } else {
                            group.skip();
                        }
                    }
                    default -> group.skip();
                }
            }
        }
        return block.build();
    }

    private static String[] stringTable(ProtoReader table) throws PbfFormatException {
        List<String> strings = new ArrayList<>();
        while (table.next()) {
            if (table.field() == 1) {
                strings.add(table.string());
            } else {
                table.skip();
            }
        }
        return strings.toArray(String[]::new);
    }

    private static final class BlockBuilder {

        private final String[] strings;
        private final long granularity;
        private final long latOffset;
        private final long lonOffset;
        private final LongList nodeIds = new LongList(8000);
        private final LongList latE7 = new LongList(8000);
        private final LongList lonE7 = new LongList(8000);
        private final List<OsmNode> taggedNodes = new ArrayList<>();
        private final List<OsmWay> ways = new ArrayList<>();
        private final List<OsmRelation> relations = new ArrayList<>();

        BlockBuilder(String[] strings, long granularity, long latOffset, long lonOffset) {
            this.strings = strings;
            this.granularity = granularity;
            this.latOffset = latOffset;
            this.lonOffset = lonOffset;
        }

        void node(ProtoReader node, boolean positions, boolean tagged) throws PbfFormatException {
            long id = 0;
            long lat = 0;
            long lon = 0;
            LongList keys = new LongList(4);
            LongList values = new LongList(4);
            while (node.next()) {
                switch (node.field()) {
                    case 1 -> id = node.sint64();
                    case 2 -> node.repeatedVarints(keys, false);
                    case 3 -> node.repeatedVarints(values, false);
                    case 8 -> lat = node.sint64();
                    case 9 -> lon = node.sint64();
                    default -> node.skip();
                }
            }
            if (positions) {
                addNode(id, lat, lon);
            }
            if (tagged && keys.size() > 0) {
                taggedNodes.add(new OsmNode(id, (int) toE7(latOffset + granularity * lat),
                        (int) toE7(lonOffset + granularity * lon), tags(keys, values, "nodo " + id)));
            }
        }

        void denseNodes(ProtoReader dense, boolean positions, boolean tagged) throws PbfFormatException {
            LongList ids = new LongList(8000);
            LongList lats = new LongList(8000);
            LongList lons = new LongList(8000);
            LongList keysAndValues = new LongList(tagged ? 8000 : 4);
            while (dense.next()) {
                switch (dense.field()) {
                    case 1 -> dense.repeatedVarints(ids, true);
                    case 8 -> dense.repeatedVarints(lats, true);
                    case 9 -> dense.repeatedVarints(lons, true);
                    case 10 -> {
                        if (tagged) {
                            dense.repeatedVarints(keysAndValues, false);
                        } else {
                            dense.skip();
                        }
                    }
                    default -> dense.skip();
                }
            }
            if (lats.size() != ids.size() || lons.size() != ids.size()) {
                throw new PbfFormatException("DenseNodes con " + ids.size() + " ids, "
                        + lats.size() + " latitudes y " + lons.size() + " longitudes");
            }
            long[] decodedIds = ids.toDeltaDecodedArray();
            long[] decodedLats = lats.toDeltaDecodedArray();
            long[] decodedLons = lons.toDeltaDecodedArray();
            if (positions) {
                for (int i = 0; i < decodedIds.length; i++) {
                    addNode(decodedIds[i], decodedLats[i], decodedLons[i]);
                }
            }
            if (tagged && keysAndValues.size() > 0) {
                denseTags(decodedIds, decodedLats, decodedLons, keysAndValues);
            }
        }

        /** En DenseNodes, las etiquetas de cada nodo son pares clave-valor terminados en 0, en orden de nodo. */
        private void denseTags(long[] ids, long[] lats, long[] lons, LongList keysAndValues) throws PbfFormatException {
            int position = 0;
            for (int i = 0; i < ids.length; i++) {
                LongList keys = new LongList(2);
                LongList values = new LongList(2);
                while (true) {
                    if (position >= keysAndValues.size()) {
                        throw new PbfFormatException("DenseNodes: faltan etiquetas para el nodo " + ids[i]);
                    }
                    long key = keysAndValues.get(position++);
                    if (key == 0) {
                        break;
                    }
                    if (position >= keysAndValues.size()) {
                        throw new PbfFormatException("DenseNodes: clave sin valor en el nodo " + ids[i]);
                    }
                    keys.add(key);
                    values.add(keysAndValues.get(position++));
                }
                if (keys.size() > 0) {
                    taggedNodes.add(new OsmNode(ids[i], (int) toE7(latOffset + granularity * lats[i]),
                            (int) toE7(lonOffset + granularity * lons[i]), tags(keys, values, "nodo " + ids[i])));
                }
            }
        }

        private void addNode(long id, long lat, long lon) {
            nodeIds.add(id);
            latE7.add(toE7(latOffset + granularity * lat));
            lonE7.add(toE7(lonOffset + granularity * lon));
        }

        private static long toE7(long nanodegrees) {
            return Math.floorDiv(nanodegrees + 50, 100);
        }

        void way(ProtoReader way) throws PbfFormatException {
            long id = 0;
            LongList keys = new LongList(4);
            LongList values = new LongList(4);
            LongList refs = new LongList(16);
            while (way.next()) {
                switch (way.field()) {
                    case 1 -> id = way.varint();
                    case 2 -> way.repeatedVarints(keys, false);
                    case 3 -> way.repeatedVarints(values, false);
                    case 8 -> way.repeatedVarints(refs, true);
                    default -> way.skip();
                }
            }
            ways.add(new OsmWay(id, refs.toDeltaDecodedArray(), tags(keys, values, "vía " + id)));
        }

        void relation(ProtoReader relation) throws PbfFormatException {
            long id = 0;
            LongList keys = new LongList(4);
            LongList values = new LongList(4);
            LongList roles = new LongList(16);
            LongList memberIds = new LongList(16);
            LongList types = new LongList(16);
            while (relation.next()) {
                switch (relation.field()) {
                    case 1 -> id = relation.varint();
                    case 2 -> relation.repeatedVarints(keys, false);
                    case 3 -> relation.repeatedVarints(values, false);
                    case 8 -> relation.repeatedVarints(roles, false);
                    case 9 -> relation.repeatedVarints(memberIds, true);
                    case 10 -> relation.repeatedVarints(types, false);
                    default -> relation.skip();
                }
            }
            String what = "relación " + id;
            if (roles.size() != memberIds.size() || types.size() != memberIds.size()) {
                throw new PbfFormatException(what + " tiene " + memberIds.size()
                        + " miembros, " + roles.size() + " roles y " + types.size() + " tipos");
            }
            long[] refs = memberIds.toDeltaDecodedArray();
            List<Member> members = new ArrayList<>(refs.length);
            for (int i = 0; i < refs.length; i++) {
                members.add(new Member(memberType(types.get(i), what), refs[i], string(roles.get(i), what)));
            }
            relations.add(new OsmRelation(id, members, tags(keys, values, what)));
        }

        private MemberType memberType(long code, String what) throws PbfFormatException {
            return switch ((int) code) {
                case 0 -> MemberType.NODE;
                case 1 -> MemberType.WAY;
                case 2 -> MemberType.RELATION;
                default -> throw new PbfFormatException(what + ": tipo de miembro " + code);
            };
        }

        private Map<String, String> tags(LongList keys, LongList values, String what) throws PbfFormatException {
            if (keys.size() != values.size()) {
                throw new PbfFormatException(what + " tiene " + keys.size() + " claves y "
                        + values.size() + " valores");
            }
            if (keys.size() == 0) {
                return Map.of();
            }
            Map<String, String> tags = HashMap.newHashMap(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                tags.put(string(keys.get(i), what), string(values.get(i), what));
            }
            return Collections.unmodifiableMap(tags);
        }

        private String string(long index, String what) throws PbfFormatException {
            if (index < 0 || index >= strings.length) {
                throw new PbfFormatException(what + ": índice " + index
                        + " fuera de la tabla de " + strings.length + " cadenas");
            }
            return strings[(int) index];
        }

        OsmBlock build() {
            long[] lat = latE7.toArray();
            long[] lon = lonE7.toArray();
            int[] latInt = new int[lat.length];
            int[] lonInt = new int[lon.length];
            for (int i = 0; i < lat.length; i++) {
                latInt[i] = (int) lat[i];
                lonInt[i] = (int) lon[i];
            }
            return new OsmBlock(nodeIds.toArray(), latInt, lonInt, taggedNodes, ways, relations);
        }
    }
}
