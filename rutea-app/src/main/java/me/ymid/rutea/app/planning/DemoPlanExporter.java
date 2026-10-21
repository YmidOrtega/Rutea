package me.ymid.rutea.app.planning;

import java.nio.file.Files;
import java.nio.file.Path;

import me.ymid.rutea.app.scenario.SubaScenario;
import me.ymid.rutea.geo.RoadGraphFile;
import tools.jackson.databind.json.JsonMapper;

/** Escribe el plan de Suba que dibuja el frontend ({@code pnpm demo:data}). */
public final class DemoPlanExporter {

    private DemoPlanExporter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("uso: DemoPlanExporter <grafo.rgraph> <archivo.json> [semilla]");
            System.exit(2);
        }
        Path graphFile = Path.of(args[0]);
        Path out = Path.of(args[1]);
        long seed = args.length == 3 ? Long.parseLong(args[2]) : SubaPlanner.DEFAULT_SEED;
        if (!Files.exists(graphFile)) {
            System.err.println("No existe " + graphFile + ". Constrúyelo con: mvn -pl rutea-geo compile exec:java"
                    + " (requiere el extracto; ver data/osm/README.md)");
            System.exit(1);
        }

        long start = System.nanoTime();
        SubaScenario scenario = SubaScenario.build(RoadGraphFile.read(graphFile).graph(), seed);
        DemoPlan plan = SubaPlanner.plan(scenario, seed);
        Files.createDirectories(out.toAbsolutePath().getParent());
        JsonMapper.builder().build().writeValue(out.toFile(), plan);

        long meters = plan.routes().stream().mapToLong(DemoPlan.Route::distanceMeters).sum();
        long points = plan.routes().stream().mapToLong(route -> route.path().size()).sum();
        int trips = plan.routes().stream().mapToInt(route -> route.tripLoadsKg().length).sum();
        System.out.printf("%d contenedores, %d camiones, %d viajes, %.1f km, %d puntos en %.1f s → %s (%d KB)%n",
                plan.containers().size(), plan.routes().size(), trips, meters / 1000.0, points,
                (System.nanoTime() - start) / 1e9, out.toAbsolutePath(), Files.size(out) / 1024);
    }
}
