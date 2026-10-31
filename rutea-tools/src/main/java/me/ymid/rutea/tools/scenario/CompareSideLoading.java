package me.ymid.rutea.tools.scenario;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.SideLoading;

/**
 * Cuánto recoge el cargue lateral de cada ASE con cada llenado medio de {@link SideLoading#FILLS}, frente a sus
 * toneladas, y qué llenado haría falta para el 13 % que declara Torca. Lee el extracto de {@code data/ase}.
 *
 * <p>Uso: {@code CompareSideLoading <dir-extracto> <dir-salida>}
 */
public final class CompareSideLoading {

    private static final DateTimeFormatter RUN_ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private CompareSideLoading() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("uso: CompareSideLoading <dir-extracto> <dir-salida>");
            System.exit(2);
        }
        Path extract = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        List<String> rows = new ArrayList<>();
        System.out.println("| ASE | Contenedores | m³ | Veces por semana | Llenado | t/día | Parte de las toneladas |");
        System.out.println("|---|---:|---:|---|---:|---:|---:|");
        for (SideLoading.Area area : SideLoading.AREAS.subList(0, 4)) {
            String source;
            List<Integer> liters;
            OperatorContainers.Layer layer = OperatorContainers.LAYERS.stream()
                    .filter(l -> l.area() == area.area().number()).findFirst().orElse(null);
            if (layer != null) {
                source = "real";
                liters = OperatorContainers.read(extract.resolve(layer.file())).stream()
                        .map(OperatorContainers.Container::liters).toList();
            } else {
                source = "plan";
                liters = Collections.nCopies(SideLoading.ASE_3_CONTAINERS, SideLoading.ASE_3_LITERS);
            }
            long totalLiters = liters.stream().mapToLong(Integer::longValue).sum();
            for (int frequency : area.frequencies()) {
                boolean assumed = !area.frequencyPublished() || frequency != area.collectionsPerWeek();
                for (double fill : SideLoading.FILLS) {
                    double kgPerDay = SideLoading.kgPerDay(liters, frequency, fill);
                    double share = area.share(kgPerDay).orElseThrow();
                    rows.add(String.format(Locale.ROOT, "%d,%s,%d,%.1f,%d,%b,%.2f,%.1f,%.1f,%.4f",
                            area.area().number(), source, liters.size(), totalLiters / 1000.0, frequency, assumed,
                            fill, kgPerDay / 1000, area.tonnesPerDay().orElseThrow(), share));
                    System.out.printf(Locale.ROOT, "| %d | %,d (%s) | %,.0f | %d%s | %.0f %% | %.1f | %.1f %% |%n",
                            area.area().number(), liters.size(), source, totalLiters / 1000.0, frequency,
                            assumed ? " (supuesto)" : "", fill * 100, kgPerDay / 1000, share * 100);
                }
            }
        }

        SideLoading.Area suba = SideLoading.of(ServiceAreas.ASE_5);
        long declared = ServiceAreas.ASE_5.containerKgPerDay();
        System.out.printf(Locale.ROOT, "%nASE 5: el 13 %% declarado son %,d kg/día. Llenado que haría falta con %d"
                + " contenedores, %d veces por semana:%n", declared, SideLoading.ASE_5_CONTAINERS,
                suba.collectionsPerWeek());
        for (int size : ServiceAreas.ASE_5.verified().containerLiters()) {
            double full = SideLoading.kgPerDay(Collections.nCopies(SideLoading.ASE_5_CONTAINERS, size),
                    suba.collectionsPerWeek(), 1.0);
            double fill = declared / full;
            rows.add(String.format(Locale.ROOT, "5,plan,%d,%.1f,%d,false,%.2f,%.1f,,", SideLoading.ASE_5_CONTAINERS,
                    SideLoading.ASE_5_CONTAINERS * size / 1000.0, suba.collectionsPerWeek(), fill, declared / 1000.0));
            System.out.printf(Locale.ROOT, "  todos de %,d L: %.0f %%%n", size, fill * 100);
        }

        Files.createDirectories(outDir);
        Path csv = outDir.resolve("side-loading-" + LocalDateTime.now().format(RUN_ID) + ".csv");
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardOpenOption.CREATE_NEW)) {
            out.write("area,containers_source,containers,m3,collections_per_week,frequency_assumed,fill,"
                    + "tonnes_per_day,area_tonnes_per_day,share\n");
            for (String row : rows) {
                out.write(row);
                out.write('\n');
            }
        }
        System.out.println("CSV: " + csv.toAbsolutePath());
    }
}
