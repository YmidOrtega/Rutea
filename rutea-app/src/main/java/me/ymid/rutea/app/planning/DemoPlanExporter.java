package me.ymid.rutea.app.planning;

import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.json.JsonMapper;

public final class DemoPlanExporter {

    static final long DEFAULT_SEED = 7;

    private DemoPlanExporter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("uso: DemoPlanExporter <archivo.json> [semilla]");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        long seed = args.length == 2 ? Long.parseLong(args[1]) : DEFAULT_SEED;

        DemoPlan plan = DemoPlanner.plan(seed);
        Path parent = out.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        JsonMapper.builder().build().writeValue(out.toFile(), plan);

        long meters = plan.routes().stream().mapToLong(DemoPlan.Route::distanceMeters).sum();
        System.out.printf("%d contenedores, %d rutas, %.1f km → %s (%d KB)%n",
                plan.containers().size(), plan.routes().size(), meters / 1000.0,
                out.toAbsolutePath(), Files.size(out) / 1024);
    }
}
