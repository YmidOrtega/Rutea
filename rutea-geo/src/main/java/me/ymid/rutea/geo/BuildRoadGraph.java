package me.ymid.rutea.geo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Compila el grafo de un área ({@link AreaSpec#SUBA} si no se dice otra) desde el extracto PBF y lo guarda en
 * {@code .rgraph}. Si el archivo ya existe y salió del mismo extracto y recorte, solo lo carga.
 *
 * <p>Uso: {@code BuildRoadGraph <extracto.osm.pbf> <salida.rgraph> [--area suba|bogota] [--force]}
 */
public final class BuildRoadGraph {

    private static final String USAGE = "uso: BuildRoadGraph <extracto.osm.pbf> <salida.rgraph> [--area suba|bogota]"
            + " [--force]";

    private static final Map<String, AreaSpec> AREAS = Map.of("suba", AreaSpec.SUBA, "bogota", AreaSpec.BOGOTA);

    private BuildRoadGraph() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println(USAGE);
            System.exit(2);
        }
        Path pbf = Path.of(args[0]);
        Path out = Path.of(args[1]);
        AreaSpec spec = AreaSpec.SUBA;
        boolean force = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--force" -> force = true;
                case "--area" -> {
                    spec = i + 1 < args.length ? AREAS.get(args[++i]) : null;
                    if (spec == null) {
                        System.err.println(USAGE);
                        System.exit(2);
                    }
                }
                default -> {
                    System.err.println(USAGE);
                    System.exit(2);
                }
            }
        }
        if (!Files.exists(pbf)) {
            System.err.println("No existe " + pbf + ". Descárgalo como dice data/osm/README.md.");
            System.exit(1);
        }

        long start = System.nanoTime();
        byte[] sha = RoadGraphFile.sha256(pbf);
        System.out.printf(Locale.ROOT, "SHA-256 %s (%s)%n", RoadGraphFile.hex(sha), elapsed(start));

        if (!force) {
            long loadStart = System.nanoTime();
            Optional<RoadGraph> current = RoadGraphFile.readIfCurrent(out, sha, spec);
            if (current.isPresent()) {
                RoadGraph graph = current.get();
                System.out.printf(Locale.ROOT, "%s está al día: %d vértices y %d arcos, cargado en %s%n", out,
                        graph.vertexCount(), graph.edgeCount(), elapsed(loadStart));
                return;
            }
        }

        long buildStart = System.nanoTime();
        RoadGraphBuilder.Result result = RoadGraphBuilder.build(pbf, spec);
        String buildTime = elapsed(buildStart);
        RoadGraphBuilder.Report r = result.report();
        RoadGraph graph = result.graph();
        System.out.printf(Locale.ROOT, """
                Construido en %s
                  vías candidatas en el extracto: %,d; dentro del recorte: %,d
                  antes de podar: %,d vértices (%,d en la localidad), %,d arcos
                  componente fuertemente conexa: %,d vértices (%,d en la localidad), %,d arcos, %,d trazos
                  calles: %.1f km
                """, buildTime, r.candidateWays(), r.includedWays(), r.verticesBeforePruning(),
                r.localityVerticesBeforePruning(), r.edgesBeforePruning(), r.vertices(), r.localityVertices(),
                r.edges(), r.shapes(), r.streetMeters() / 1000.0);
        int center = r.localityCenterVertex();
        int depot = r.siteVertices().get(AreaSpec.DEPOT);
        int disposal = r.siteVertices().get(AreaSpec.DISPOSAL);
        System.out.printf(Locale.ROOT, "  centro de la localidad: vértice %d (%.5f, %.5f)%n", center,
                graph.latE7(center) / 1e7, graph.lonE7(center) / 1e7);
        printTrip(graph, "centro de la localidad → relleno", center, disposal);
        printTrip(graph, "relleno → centro de la localidad", disposal, center);
        printTrip(graph, "base → centro de la localidad", depot, center);
        printTrip(graph, "relleno → base", disposal, depot);

        long writeStart = System.nanoTime();
        Files.createDirectories(out.toAbsolutePath().getParent());
        RoadGraphFile.write(out, graph, sha, spec);
        System.out.printf(Locale.ROOT, "Guardado %s (%,d bytes) en %s%n", out, Files.size(out), elapsed(writeStart));
    }

    /** Distancia y tiempo de un viaje por el grafo: la prueba de que el corredor conecta los sitios. */
    private static void printTrip(RoadGraph graph, String name, int from, int to) {
        int meters = new ShortestPaths(graph, ShortestPaths.Weight.METERS).distances(from, new int[] {to})[0];
        int seconds = new ShortestPaths(graph, ShortestPaths.Weight.SECONDS).distances(from, new int[] {to})[0];
        System.out.printf(Locale.ROOT, "  %s: %.1f km, %d min con las velocidades supuestas%n", name,
                meters / 1000.0, Math.round(seconds / 60.0));
    }

    private static String elapsed(long startNanos) {
        return String.format(Locale.ROOT, "%.2f s", (System.nanoTime() - startNanos) / 1e9);
    }
}
