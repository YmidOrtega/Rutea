package me.ymid.rutea.app.planning;

import java.nio.file.Files;
import java.nio.file.Path;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.geo.RoadGraphFile;
import tools.jackson.databind.json.JsonMapper;

/** Escribe el plan de Suba que dibuja el frontend ({@code pnpm demo:data}). */
public final class DemoPlanExporter {

    private DemoPlanExporter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            System.err.println("uso: DemoPlanExporter <grafo.rgraph> <sitios.csv> <archivo.json> [semilla]");
            System.exit(2);
        }
        Path graphFile = Path.of(args[0]);
        Path sitesFile = Path.of(args[1]);
        Path out = Path.of(args[2]);
        long seed = args.length == 4 ? Long.parseLong(args[3]) : AreaPlanner.DEFAULT_SEED;
        if (!Files.exists(graphFile)) {
            System.err.println("No existe " + graphFile + ". Constrúyelo con: mvn -pl rutea-geo compile exec:java"
                    + " (requiere el extracto; ver data/osm/README.md)");
            System.exit(1);
        }

        if (!Files.exists(sitesFile)) {
            System.err.println("No existe " + sitesFile + ". Extráelo con: mvn -pl rutea-app exec:java@sensitive-sites");
            System.exit(1);
        }

        long start = System.nanoTime();
        AreaScenario scenario = AreaScenario.build(RoadGraphFile.read(graphFile).graph(), ServiceAreas.ASE_5, seed)
                .withSites(SensitiveSites.read(sitesFile));
        DemoPlan plan = AreaPlanner.planBoth(scenario,
                AreaScenario.buildRecyclable(scenario.graph(), scenario.area(), seed).withSites(scenario.sites()), seed);
        Files.createDirectories(out.toAbsolutePath().getParent());
        JsonMapper.builder().build().writeValue(out.toFile(), plan);

        long meters = plan.routes().stream().mapToLong(DemoPlan.Route::distanceMeters).sum();
        long points = plan.routes().stream().mapToLong(route -> route.path().size()).sum();
        int trips = plan.routes().stream().mapToInt(route -> route.tripLoadsKg().length).sum();
        System.out.printf("%d contenedores, %d vehículos, %d viajes, %.1f km, %d puntos en %.1f s → %s (%d KB)%n",
                plan.containers().size(), plan.routes().size(), trips, meters / 1000.0, points,
                (System.nanoTime() - start) / 1e9, out.toAbsolutePath(), Files.size(out) / 1024);
    }
}
