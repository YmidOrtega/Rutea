package me.ymid.rutea.app.planning;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import me.ymid.rutea.app.scenario.AreaScenario;
import me.ymid.rutea.app.scenario.BogotaFigures;
import me.ymid.rutea.app.scenario.DoorToDoor;
import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.SideLoading;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;
import me.ymid.rutea.solver.Evaluation;
import me.ymid.rutea.solver.Evaluator;

public final class CheckLiveLocalities {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private CheckLiveLocalities() {
    }

    record Result(ServiceAreas.Locality locality, DoorToDoor.Slot slot, Optional<Measure> measure) {
    }

    record Measure(int containers, int chunks, long kg, int trucks, int trips, double km, int lastTruckSeconds,
            int shiftSeconds, boolean feasible, List<String> problems, double buildSeconds) {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.err.println("uso: CheckLiveLocalities <bogota.rgraph> <bogota-sitios.csv> <data/ase> <dir-salida>");
            System.exit(2);
        }
        RoadGraph graph = RoadGraphFile.read(Path.of(args[0])).graph();
        List<SensitiveSites.Site> sites = SensitiveSites.read(Path.of(args[1]));
        Map<Integer, List<OperatorContainers.Container>> real = new LinkedHashMap<>();
        for (OperatorContainers.Layer layer : OperatorContainers.LAYERS) {
            real.put(layer.area(), OperatorContainers.read(Path.of(args[2]).resolve(layer.file())));
        }
        long seed = AreaPlanner.DEFAULT_SEED;

        Path outDir = Path.of(args[3]);
        Files.createDirectories(outDir);
        Path csv = outDir.resolve("live-localities-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        List<Result> results = new ArrayList<>();
        long start = System.nanoTime();
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("locality,name,area,slot,seed,iterations,containers,chunks,kg,trucks,trips,km,"
                    + "last_truck_s,shift_s,feasible,problems,build_s\n");
            for (ServiceAreas.ServiceArea area : ServiceAreas.BOGOTA) {
                for (ServiceAreas.Locality locality : area.localities()) {
                    if (locality.rural()) {
                        continue;
                    }
                    for (Result result : check(graph, sites, real, area, locality, seed)) {
                        results.add(result);
                        write(out, area, result, seed);
                        out.flush();
                        print(result);
                    }
                }
            }
        }
        long planned = results.stream().filter(r -> r.measure().isPresent()).count();
        long failed = results.stream().filter(r -> r.measure().filter(m -> !m.problems().isEmpty()).isPresent())
                .count();
        double[] seconds = results.stream().flatMap(r -> r.measure().stream()).filter(m -> m.problems().isEmpty())
                .mapToDouble(Measure::buildSeconds).sorted().toArray();
        System.out.printf(Locale.ROOT, "%n%d franjas: %d planificadas, %d sin nada que recoger, %d con problemas;"
                + " armado P50 %.1f s, máx. %.1f s; %.0f s en total%n", results.size(), planned,
                results.size() - planned, failed, seconds[seconds.length / 2], seconds[seconds.length - 1],
                (System.nanoTime() - start) / 1e9);
        System.out.println("CSV: " + csv.toAbsolutePath());
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static List<Result> check(RoadGraph graph, List<SensitiveSites.Site> sites,
            Map<Integer, List<OperatorContainers.Container>> real, ServiceAreas.ServiceArea area,
            ServiceAreas.Locality locality, long seed) {
        long start = System.nanoTime();
        DoorToDoor.Plan plan = DoorToDoor.plan(graph, area, locality, Sectors.of(area)::locate,
                DoorToDoor.containers(graph, area, locality, real, SideLoading.MEAN_FILL, seed), "");
        double planSeconds = (System.nanoTime() - start) / 1e9 / plan.turns().size();
        List<Result> results = new ArrayList<>();
        for (DoorToDoor.Slot slot : DoorToDoor.Slot.ALL) {
            Optional<DoorToDoor.Turn> turn = plan.turns().stream().filter(t -> t.slot().equals(slot)).findFirst();
            results.add(new Result(locality, slot, turn.map(t -> {
                try {
                    return measure(DoorToDoor.scenario(graph, area, plan, t).withSites(sites), seed, planSeconds);
                } catch (RuntimeException e) {
                    return failed(e.getMessage());
                }
            })));
        }
        return results;
    }

    private static Measure measure(AreaScenario scenario, long seed, double planSeconds) {
        long start = System.nanoTime();
        AreaPlanner.Fleet fleet = AreaPlanner.fleet(scenario, BogotaFigures.UNLOAD_SECONDS_LOW, seed);
        double seconds = planSeconds + (System.nanoTime() - start) / 1e9;
        Evaluation evaluation = Evaluator.evaluate(scenario.instance(), fleet.solution());
        List<String> problems = new ArrayList<>(evaluation.violations());
        DemoPlan plan = fleet.plan();
        Set<String> seen = new HashSet<>();
        int stops = 0;
        for (DemoPlan.Route route : plan.routes()) {
            for (String stop : route.stops()) {
                stops++;
                if (!seen.add(stop)) {
                    problems.add(stop + " repetida en el dibujo");
                }
            }
        }
        int customers = scenario.instance().customerCount();
        if (stops != customers) {
            problems.add("el dibujo pasa por " + stops + " paradas de " + customers);
        }
        int shift = fleet.schedule().shiftSeconds();
        int last = (int) fleet.schedule().trucks().stream().mapToLong(ShiftScheduler.Truck::seconds).max().orElse(0);
        if (last > shift) {
            problems.add("un camión termina a los " + last + " s de un turno de " + shift);
        }
        return new Measure(plan.containers().size(), plan.streets().size(),
                plan.routes().stream().mapToLong(DemoPlan.Route::loadKg).sum(), plan.routes().size(),
                plan.routes().stream().mapToInt(route -> route.tripLoadsKg().length).sum(),
                PlanReport.of(plan).total().km(), last, shift, evaluation.feasible(), problems, seconds);
    }

    private static Measure failed(String problem) {
        return new Measure(0, 0, 0, 0, 0, 0, 0, 0, false, List.of(problem), 0);
    }

    private static void write(BufferedWriter out, ServiceAreas.ServiceArea area, Result result, long seed)
            throws IOException {
        String head = String.format(Locale.ROOT, "%d,%s,%d,%s,%d,%d", result.locality().number(),
                result.locality().name(), area.number(), result.slot().code(), seed, AreaPlanner.ITERATIONS);
        if (result.measure().isEmpty()) {
            out.write(head + ",0,0,0,0,0,,,,,sin nada que recoger,\n");
            return;
        }
        Measure m = result.measure().get();
        out.write(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%.1f,%d,%d,%b,\"%s\",%.1f%n", head, m.containers(),
                m.chunks(), m.kg(), m.trucks(), m.trips(), m.km(), m.lastTruckSeconds(), m.shiftSeconds(),
                m.feasible(), String.join("; ", m.problems()).replace("\"", "'"), m.buildSeconds()));
    }

    private static void print(Result result) {
        String name = result.locality().name() + " " + result.slot().code();
        if (result.measure().isEmpty()) {
            System.out.println(name + ": sin nada que recoger");
            return;
        }
        Measure m = result.measure().get();
        System.out.printf(Locale.ROOT, "%s: %d contenedores, %d trozos, %d camiones, %d viajes, %.1f km, %.1f s%s%n",
                name, m.containers(), m.chunks(), m.trucks(), m.trips(), m.km(), m.buildSeconds(),
                m.problems().isEmpty() ? "" : " · " + String.join("; ", m.problems()));
    }
}
