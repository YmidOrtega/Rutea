package me.ymid.rutea.geo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import me.ymid.rutea.geo.osm.PbfFixture;
import me.ymid.rutea.geo.osm.PbfFixture.Block;
import me.ymid.rutea.geo.osm.PbfFixture.Compression;

/**
 * Pueblo de prueba compartido por los tests de rutea-geo y de rutea-app, en pasos de 0,001° (unos 111 m) desde (4,70; -74,00):
 *
 * <pre>
 *   localidad (relación 100): cuadrado (0,0)–(10,10), en dos vías para probar el armado del anillo
 *   ciudad (relación 200): (-20,-20)–(30,40)
 *   relleno (vía 3000): (-15,30)–(-10,35)
 *
 *   A(2,2) ─24─ B(2,8) ─ 32    cuadrado residencial de doble sentido A-B-C-D
 *   │            │             B ─ 32: doble sentido sin salida (se queda)
 *   │            │             D → 25: sentido único sin salida (se poda)
 *   D(8,2) ──── C(8,8) ══ 28 ══ 29 ── 30   primaria C-29 y vía de servicio 29-30 junto al relleno
 * </pre>
 */
public final class SampleTown {

    public static final AreaSpec SPEC = new AreaSpec(100, 200, 3000, 500);

    private static final Map<Long, int[]> NODES = new TreeMap<>();

    static {
        node(1, 0, 0);
        node(2, 0, 10);
        node(3, 10, 10);
        node(4, 10, 0);
        node(5, -20, -20);
        node(6, -20, 40);
        node(7, 30, 40);
        node(8, 30, -20);
        node(9, -15, 30);
        node(10, -15, 35);
        node(11, -10, 35);
        node(12, -10, 30);
        node(20, 2, 2);
        node(21, 2, 8);
        node(22, 8, 8);
        node(23, 8, 2);
        node(24, 2, 5);
        node(25, 9, 1);
        node(26, 1, 1);
        node(27, 3, 9);
        node(28, 5, 20);
        node(29, -12, 29);
        node(30, -12, 32);
        node(31, 6, 22);
        node(32, 4, 8);
    }

    private static void node(long id, int latSteps, int lonSteps) {
        NODES.put(id, new int[] {47_000_000 + 10_000 * latSteps, -740_000_000 + 10_000 * lonSteps});
    }

    private SampleTown() {
    }

    /** Coordenadas {lat, lon} en 1e-7 grados de cada nodo, por id de OSM. */
    public static Map<Long, int[]> nodes() {
        return Collections.unmodifiableMap(NODES);
    }

    public static Path writePbf(Path dir) throws IOException {
        long[] ids = NODES.keySet().stream().mapToLong(Long::longValue).toArray();
        long[] lat = new long[ids.length];
        long[] lon = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            lat[i] = NODES.get(ids[i])[0] * 100L;
            lon[i] = NODES.get(ids[i])[1] * 100L;
        }
        Block ways = new Block()
                .way(1000, new long[] {1, 2, 3})
                .way(1001, new long[] {1, 4, 3})
                .way(2000, new long[] {5, 6, 7, 8, 5})
                .way(3000, new long[] {9, 10, 11, 12, 9}, "landuse", "landfill")
                .way(5000, new long[] {20, 24, 21, 22}, "highway", "residential")
                .way(5001, new long[] {22, 23, 20}, "highway", "residential")
                .way(5002, new long[] {23, 25}, "highway", "residential", "oneway", "yes")
                .way(5003, new long[] {20, 26}, "highway", "footway")
                .way(5004, new long[] {21, 27}, "highway", "residential", "access", "private")
                .way(5005, new long[] {21, 32}, "highway", "residential")
                .way(6000, new long[] {22, 28, 29}, "highway", "primary")
                .way(6001, new long[] {29, 30}, "highway", "service")
                .way(6002, new long[] {28, 31}, "highway", "residential");
        Block relations = new Block()
                .relation(100, new int[] {1, 1}, new long[] {1000, 1001}, new String[] {"outer", "outer"},
                        "boundary", "administrative")
                .relation(200, new int[] {1}, new long[] {2000}, new String[] {"outer"},
                        "boundary", "administrative");
        return new PbfFixture().standardHeader()
                .data(new Block().denseNodes(ids, lat, lon), Compression.ZLIB)
                .data(ways, Compression.ZLIB)
                .data(relations, Compression.ZLIB)
                .write(dir);
    }

    public static RoadGraph graph(Path dir) throws IOException {
        return RoadGraphBuilder.build(writePbf(dir), SPEC).graph();
    }
}
