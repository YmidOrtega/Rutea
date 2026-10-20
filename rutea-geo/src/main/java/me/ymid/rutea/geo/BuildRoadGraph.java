package me.ymid.rutea.geo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Compila el grafo de {@link AreaSpec#SUBA} desde el extracto PBF y lo guarda en {@code .rgraph}. Si el
 * archivo ya existe y salió del mismo extracto y recorte, solo lo carga.
 *
 * <p>Uso: {@code BuildRoadGraph <extracto.osm.pbf> <salida.rgraph> [--force]}
 */
public final class BuildRoadGraph {

    private BuildRoadGraph() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("uso: BuildRoadGraph <extracto.osm.pbf> <salida.rgraph> [--force]");
            System.exit(2);
        }
        Path pbf = Path.of(args[0]);
        Path out = Path.of(args[1]);
        boolean force = args.length > 2 && args[2].equals("--force");
        if (!Files.exists(pbf)) {
            System.err.println("No existe " + pbf + ". Descárgalo como dice data/osm/README.md.");
            System.exit(1);
        }

        long start = System.nanoTime();
        byte[] sha = RoadGraphFile.sha256(pbf);
        System.out.printf(Locale.ROOT, "SHA-256 %s (%s)%n", RoadGraphFile.hex(sha), elapsed(start));

        if (!force) {
            long loadStart = System.nanoTime();
            Optional<RoadGraph> current = RoadGraphFile.readIfCurrent(out, sha, AreaSpec.SUBA);
            if (current.isPresent()) {
                RoadGraph graph = current.get();
                System.out.printf(Locale.ROOT, "%s está al día: %d vértices y %d arcos, cargado en %s%n", out,
                        graph.vertexCount(), graph.edgeCount(), elapsed(loadStart));
                return;
            }
        }

        long buildStart = System.nanoTime();
        RoadGraphBuilder.Result result = RoadGraphBuilder.build(pbf, AreaSpec.SUBA);
        String buildTime = elapsed(buildStart);
        RoadGraphBuilder.Report r = result.report();
        RoadGraph graph = result.graph();
        System.out.printf(Locale.ROOT, """
                Construido en %s
                  vías candidatas en el extracto: %,d; dentro del recorte: %,d
                  antes de podar: %,d vértices (%,d en la localidad), %,d arcos
                  componente fuertemente conexa: %,d vértices (%,d en la localidad), %,d arcos, %,d trazos
                  calles: %.1f km
                  vértice más cercano al centro del destino: %d (OSM %d), a %,d m
                """, buildTime, r.candidateWays(), r.includedWays(), r.verticesBeforePruning(),
                r.localityVerticesBeforePruning(), r.edgesBeforePruning(), r.vertices(), r.localityVertices(),
                r.edges(), r.shapes(), r.streetMeters() / 1000.0, r.destinationVertex(),
                graph.osmNodeId(r.destinationVertex()), r.destinationVertexMeters());
        printTrip(graph, r.localityCenterVertex(), r.destinationVertex());

        long writeStart = System.nanoTime();
        Files.createDirectories(out.toAbsolutePath().getParent());
        RoadGraphFile.write(out, graph, sha, AreaSpec.SUBA);
        System.out.printf(Locale.ROOT, "Guardado %s (%,d bytes) en %s%n", out, Files.size(out), elapsed(writeStart));
    }

    /** Ida y vuelta entre el centro de la localidad y el destino: la prueba de que el corredor conecta. */
    private static void printTrip(RoadGraph graph, int locality, int destination) {
        ShortestPaths meters = new ShortestPaths(graph, ShortestPaths.Weight.METERS);
        ShortestPaths seconds = new ShortestPaths(graph, ShortestPaths.Weight.SECONDS);
        System.out.printf(Locale.ROOT, "  centro de la localidad: vértice %d (%.5f, %.5f)%n", locality,
                graph.latE7(locality) / 1e7, graph.lonE7(locality) / 1e7);
        int[][] legs = {{locality, destination}, {destination, locality}};
        String[] names = {"ida al destino", "vuelta a la localidad"};
        for (int i = 0; i < legs.length; i++) {
            int distance = meters.distances(legs[i][0], new int[] {legs[i][1]})[0];
            int s = seconds.distances(legs[i][0], new int[] {legs[i][1]})[0];
            System.out.printf(Locale.ROOT, "  %s: %.1f km, %d min con las velocidades supuestas%n", names[i],
                    distance / 1000.0, Math.round(s / 60.0));
        }
    }

    private static String elapsed(long startNanos) {
        return String.format(Locale.ROOT, "%.2f s", (System.nanoTime() - startNanos) / 1e9);
    }
}
