package me.ymid.rutea.app.planning;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.DoorToDoor;
import me.ymid.rutea.app.scenario.Localities;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.app.scenario.StreetChunks;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.geo.geometry.Haversine;
import me.ymid.rutea.solver.Evaluation;
import me.ymid.rutea.solver.Evaluator;
import me.ymid.rutea.solver.Instance;
import me.ymid.rutea.solver.Solution;
import me.ymid.rutea.solver.SolverSettings;
import me.ymid.rutea.solver.alns.AlnsSolver;

/**
 * Cuánto agrupar los tramos del puerta a puerta (ADR 0014): para cada longitud máxima de trozo, cuántos clientes salen,
 * cuánto tarda la matriz, cuánto tarda ALNS y cuánto mide el plan. En toda Suba no se puede comparar contra los tramos
 * sueltos (9.121 clientes), así que la pérdida por agrupar se mide en una zona chica de Suba, donde sí cabe.
 *
 * <p>La instancia es una recolección de toda la localidad: las toneladas de la ciudad repartidas por población, por
 * los días que se acumulan entre dos pasadas. Es una cota alta del tamaño: en la operación, cada día sale solo una
 * parte de las calles. El costo incluye lo que se recorre recogiendo, no solo los traslados.
 *
 * <p>Uso: {@code MeasureDoorToDoor <suba.rgraph> <dir-salida>}
 */
public final class MeasureDoorToDoor {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    static final int[] MAX_METERS = {0, 150, 300, 500, 800, 1_200};

    /** Radio de la zona chica: unos cientos de tramos, para que los tramos sueltos todavía se resuelvan. */
    static final int ZONE_RADIUS_METERS = 900;

    private MeasureDoorToDoor() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("uso: MeasureDoorToDoor <suba.rgraph> <dir-salida>");
            System.exit(2);
        }
        RoadGraph graph = RoadGraphFile.read(Path.of(args[0])).graph();
        ServiceAreas.ServiceArea area = ServiceAreas.ASE_5;
        IntPredicate suba = AreaScenario.streets(graph, area, Localities.bogota());
        // La base y la portería, las mismas del cargue lateral de 2026.
        AreaScenario sideLoading = AreaScenario.buildPlanned(graph, area, SideLoading.ASE_5_NIGHT,
                SideLoading.ASE_5_CONTAINERS, area.verified().containerLiters(), SideLoading.MEAN_FILL,
                AreaPlanner.DEFAULT_SEED);
        int depot = sideLoading.depotVertex();
        int gate = sideLoading.gateVertex();
        int capacity = area.verified().compactorKg();

        double kgPerDay = BogotaFigures.kgPerDay(ServiceAreas.locality(11).orElseThrow());
        double kgPerCollection = kgPerDay * 7 / DoorToDoor.COLLECTIONS_PER_WEEK;
        double localMeters = StreetChunks.of(graph, suba, 0).stream().mapToLong(StreetChunks.Chunk::meters).sum();
        double kgPerMeter = kgPerCollection / localMeters;
        IntPredicate zone = zone(graph, suba);
        long seed = AreaPlanner.DEFAULT_SEED;
        long iterations = AreaPlanner.ITERATIONS;

        System.out.printf(Locale.ROOT, "Suba: %.1f t/día, %.1f t por recolección (%d por semana), %.1f km de calle"
                + " local, %.3f kg/m; ALNS con semilla %d y %d iteraciones%n%n", kgPerDay / 1000,
                kgPerCollection / 1000, DoorToDoor.COLLECTIONS_PER_WEEK, localMeters / 1000, kgPerMeter, seed, iterations);
        System.out.println("| Zona | Trozo máx. | Clientes | m por trozo | Matriz | ALNS | km | Viajes |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|");

        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);
        Path csv = outDir.resolve("door-to-door-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("zone,max_chunk_m,customers,mean_chunk_m,local_m,kg_per_m,capacity_kg,seed,iterations,"
                    + "matrix_ms,alns_ms,solution_m,collecting_m,trips,feasible\n");
            for (int max : MAX_METERS) {
                measure(out, "zona", graph, zone, max, depot, gate, capacity, kgPerMeter, seed, iterations);
            }
            for (int max : MAX_METERS) {
                if (max > 0) {
                    measure(out, "suba", graph, suba, max, depot, gate, capacity, kgPerMeter, seed, iterations);
                }
            }
        }
        System.out.println("\nCSV: " + csv.toAbsolutePath());
    }

    private static void measure(BufferedWriter out, String name, RoadGraph graph, IntPredicate area, int maxMeters,
            int depot, int gate, int capacity, double kgPerMeter, long seed, long iterations) throws IOException {
        List<StreetChunks.Chunk> chunks = StreetChunks.of(graph, area, maxMeters);
        long started = System.nanoTime();
        Instance instance = DoorToDoor.instance(graph, List.of(), chunks, depot, gate, capacity, kgPerMeter,
                String.format(Locale.ROOT, "%s-d2d-%d", name, maxMeters));
        long matrixMs = (System.nanoTime() - started) / 1_000_000;
        started = System.nanoTime();
        Solution solution = new AlnsSolver().solve(instance, new SolverSettings(seed, Duration.ofHours(1), iterations));
        long alnsMs = (System.nanoTime() - started) / 1_000_000;
        Evaluation evaluation = Evaluator.evaluate(instance, solution);
        long collecting = chunks.stream().mapToLong(StreetChunks.Chunk::meters).sum();
        out.write(String.format(Locale.ROOT, "%s,%d,%d,%.1f,%d,%.4f,%d,%d,%d,%d,%d,%d,%d,%d,%s%n", name, maxMeters,
                chunks.size(), (double) collecting / chunks.size(), collecting, kgPerMeter, capacity, seed, iterations,
                matrixMs, alnsMs, evaluation.cost(), collecting, solution.routeCount(), evaluation.feasible()));
        out.flush();
        System.out.printf(Locale.ROOT, "| %s | %d m | %d | %.0f | %d ms | %.1f s | %.1f | %d |%s%n", name, maxMeters,
                chunks.size(), (double) collecting / chunks.size(), matrixMs, alnsMs / 1000.0,
                evaluation.cost() / 1000.0, solution.routeCount(), evaluation.feasible() ? "" : " INFACTIBLE");
    }

    /** Los tramos de Suba a menos de {@link #ZONE_RADIUS_METERS} del centro de sus calles locales. */
    private static IntPredicate zone(RoadGraph graph, IntPredicate suba) {
        List<StreetChunks.Chunk> all = StreetChunks.of(graph, suba, 0);
        double lat = 0;
        double lon = 0;
        for (StreetChunks.Chunk chunk : all) {
            lat += graph.latE7(chunk.entry());
            lon += graph.lonE7(chunk.entry());
        }
        int centerLat = (int) Math.round(lat / all.size());
        int centerLon = (int) Math.round(lon / all.size());
        return shape -> {
            if (!suba.test(shape)) {
                return false;
            }
            int point = graph.firstShapePoint(shape);
            return Haversine.meters(graph.shapeLatE7(point), graph.shapeLonE7(point), centerLat, centerLon)
                    <= ZONE_RADIUS_METERS;
        };
    }
}
