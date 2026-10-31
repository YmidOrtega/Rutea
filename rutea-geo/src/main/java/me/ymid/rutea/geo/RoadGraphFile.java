package me.ymid.rutea.geo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Formato binario propio del grafo compilado ({@code .rgraph}), little-endian:
 *
 * <pre>
 * "RUTEAGRF" · versión · SHA-256 del PBF de origen
 * AreaSpec: localidad · ciudad · n sitios · (largo del nombre · nombre UTF-8 · nodo · margen) × n
 * vértices · arcos · trazos · puntos de trazo
 * ids OSM · lat · lon · primer arco · destino · metros · segundos · clase · trazo · primer punto · lat · lon
 * · trazo en la localidad (un byte por trazo)
 * CRC32 de todo lo anterior
 * </pre>
 *
 * Un archivo de otra versión, de otro extracto o de otro recorte se considera viejo y se reconstruye.
 */
public final class RoadGraphFile {

    public static final int VERSION = 3;

    private static final byte[] MAGIC = "RUTEAGRF".getBytes(StandardCharsets.US_ASCII);
    /** Encabezado sin el AreaSpec, que tiene largo variable. */
    private static final int HEADER_BYTES = MAGIC.length + 4 + 32 + 4 * 4;

    public record Stored(RoadGraph graph, byte[] sourceSha256, AreaSpec spec) {
    }

    private RoadGraphFile() {
    }

    public static void write(Path file, RoadGraph graph, byte[] sourceSha256, AreaSpec spec) throws IOException {
        if (sourceSha256.length != 32) {
            throw new IllegalArgumentException("el SHA-256 tiene 32 bytes, no " + sourceSha256.length);
        }
        int v = graph.vertexCount();
        int e = graph.edgeCount();
        int s = graph.shapeCount();
        int p = graph.shapeLatE7().length;
        byte[] specBytes = encode(spec);
        long size = HEADER_BYTES + specBytes.length + 8L * v + 4L * (v + v + v + 1) + 4L * (e + e + e + e) + e + 4L * (s + 1)
                + 4L * (p + p) + s + 4;
        if (size > Integer.MAX_VALUE - 8) {
            throw new IOException("grafo de " + size + " bytes: no cabe en un solo archivo de este formato");
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(MAGIC).putInt(VERSION).put(sourceSha256).put(specBytes)
                .putInt(v).putInt(e).putInt(s).putInt(p);
        putLongs(buffer, graph.osmNodeIds());
        putInts(buffer, graph.latE7());
        putInts(buffer, graph.lonE7());
        putInts(buffer, graph.firstEdge());
        putInts(buffer, graph.edgeTarget());
        putInts(buffer, graph.edgeMeters());
        putInts(buffer, graph.edgeSeconds());
        buffer.put(graph.edgeRoadClass());
        putInts(buffer, graph.edgeShape());
        putInts(buffer, graph.firstShapePoint());
        putInts(buffer, graph.shapeLatE7());
        putInts(buffer, graph.shapeLonE7());
        for (boolean inLocality : graph.shapeInLocality()) {
            buffer.put((byte) (inLocality ? 1 : 0));
        }
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, buffer.position());
        buffer.putInt((int) crc.getValue());

        // Se escribe al lado y se renombra: un proceso cortado nunca deja un grafo a medias.
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, buffer.array());
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static Stored read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        if (bytes.length < HEADER_BYTES + 4 || !Arrays.equals(bytes, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new IOException(name + ": no es un grafo de Rutea");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(MAGIC.length);
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new StaleGraphException(name + ": versión " + version + ", se esperaba " + VERSION);
        }
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        int stored = ByteBuffer.wrap(bytes, bytes.length - 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (stored != (int) crc.getValue()) {
            throw new IOException(name + ": el CRC32 no coincide; el archivo está dañado");
        }
        byte[] sha = new byte[32];
        buffer.get(sha);
        AreaSpec spec = decode(buffer, name);
        int v = buffer.getInt();
        int e = buffer.getInt();
        int s = buffer.getInt();
        int p = buffer.getInt();
        long[] osmIds = getLongs(buffer, v);
        int[] lat = getInts(buffer, v);
        int[] lon = getInts(buffer, v);
        int[] firstEdge = getInts(buffer, v + 1);
        int[] target = getInts(buffer, e);
        int[] meters = getInts(buffer, e);
        int[] seconds = getInts(buffer, e);
        byte[] roadClass = new byte[e];
        buffer.get(roadClass);
        int[] shape = getInts(buffer, e);
        int[] firstShapePoint = getInts(buffer, s + 1);
        int[] shapeLat = getInts(buffer, p);
        int[] shapeLon = getInts(buffer, p);
        if (buffer.remaining() < s + 4) {
            throw new IOException(name + ": grafo cortado en las marcas de localidad");
        }
        boolean[] inLocality = new boolean[s];
        for (int i = 0; i < s; i++) {
            inLocality[i] = buffer.get() != 0;
        }
        if (buffer.remaining() != 4) {
            throw new IOException(name + ": sobran " + (buffer.remaining() - 4) + " bytes");
        }
        RoadGraph graph = new RoadGraph(osmIds, lat, lon, firstEdge, target, meters, seconds, roadClass, shape,
                firstShapePoint, shapeLat, shapeLon, inLocality);
        return new Stored(graph, sha, spec);
    }

    public static Optional<RoadGraph> readIfCurrent(Path file, byte[] sourceSha256, AreaSpec spec)
            throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Stored stored;
        try {
            stored = read(file);
        } catch (StaleGraphException e) {
            return Optional.empty();
        }
        if (!Arrays.equals(stored.sourceSha256(), sourceSha256) || !stored.spec().equals(spec)) {
            return Optional.empty();
        }
        return Optional.of(stored.graph());
    }

    public static byte[] sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("toda JVM trae SHA-256", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] chunk = new byte[1 << 20];
            for (int n; (n = in.read(chunk)) > 0; ) {
                digest.update(chunk, 0, n);
            }
        }
        return digest.digest();
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] encode(AreaSpec spec) {
        List<byte[]> names = new ArrayList<>();
        int size = 8 + 8 + 4;
        for (AreaSpec.Site site : spec.sites()) {
            byte[] name = site.name().getBytes(StandardCharsets.UTF_8);
            names.add(name);
            size += 4 + name.length + 8 + 4;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(spec.localityRelation()).putLong(spec.cityRelation()).putInt(spec.sites().size());
        for (int i = 0; i < names.size(); i++) {
            AreaSpec.Site site = spec.sites().get(i);
            buffer.putInt(names.get(i).length).put(names.get(i)).putLong(site.osmNode()).putInt(site.marginMeters());
        }
        return buffer.array();
    }

    private static AreaSpec decode(ByteBuffer buffer, String name) throws IOException {
        long locality = buffer.getLong();
        long city = buffer.getLong();
        int count = buffer.getInt();
        if (count < 0 || count > 1000) {
            throw new IOException(name + ": " + count + " sitios en el encabezado");
        }
        List<AreaSpec.Site> sites = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
                throw new IOException(name + ": nombre de sitio de " + length + " bytes");
            }
            byte[] siteName = new byte[length];
            buffer.get(siteName);
            sites.add(new AreaSpec.Site(new String(siteName, StandardCharsets.UTF_8), buffer.getLong(), buffer.getInt()));
        }
        return new AreaSpec(locality, city, sites);
    }

    private static final class StaleGraphException extends IOException {

        private static final long serialVersionUID = 1L;

        StaleGraphException(String message) {
            super(message);
        }
    }

    private static void putInts(ByteBuffer buffer, int[] values) {
        buffer.asIntBuffer().put(values);
        buffer.position(buffer.position() + 4 * values.length);
    }

    private static void putLongs(ByteBuffer buffer, long[] values) {
        buffer.asLongBuffer().put(values);
        buffer.position(buffer.position() + 8 * values.length);
    }

    private static int[] getInts(ByteBuffer buffer, int count) throws IOException {
        if (count < 0 || 4L * count > buffer.remaining()) {
            throw new IOException("grafo cortado: faltan " + count + " enteros");
        }
        int[] values = new int[count];
        buffer.asIntBuffer().get(values);
        buffer.position(buffer.position() + 4 * count);
        return values;
    }

    private static long[] getLongs(ByteBuffer buffer, int count) throws IOException {
        if (count < 0 || 8L * count > buffer.remaining()) {
            throw new IOException("grafo cortado: faltan " + count + " enteros largos");
        }
        long[] values = new long[count];
        buffer.asLongBuffer().get(values);
        buffer.position(buffer.position() + 8 * count);
        return values;
    }
}
