package me.ymid.rutea.solver.cvrplib;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import me.ymid.rutea.solver.Instance;

public final class CvrplibWriter {

    private CvrplibWriter() {
    }

    /** {@code comment} va en una sola línea: sirve para la fuente y la licencia de los datos. */
    public static void writeExplicit(Path file, Instance instance, String comment) throws IOException {
        if (comment.contains("\n")) {
            throw new IllegalArgumentException("el comentario debe ir en una sola línea");
        }
        int n = instance.size();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            out.write("NAME : " + instance.name() + "\n");
            out.write("COMMENT : " + comment + "\n");
            out.write("TYPE : CVRP\n");
            out.write("DIMENSION : " + n + "\n");
            out.write("EDGE_WEIGHT_TYPE : EXPLICIT\n");
            out.write("EDGE_WEIGHT_FORMAT : FULL_MATRIX\n");
            out.write("CAPACITY : " + instance.capacity() + "\n");
            out.write("EDGE_WEIGHT_SECTION\n");
            StringBuilder row = new StringBuilder(n * 6);
            for (int i = 0; i < n; i++) {
                row.setLength(0);
                for (int j = 0; j < n; j++) {
                    if (j > 0) {
                        row.append(' ');
                    }
                    row.append(instance.distance(i, j));
                }
                out.write(row.append('\n').toString());
            }
            out.write("DEMAND_SECTION\n");
            for (int i = 0; i < n; i++) {
                out.write((i + 1) + " " + instance.demand(i) + "\n");
            }
            out.write("DEPOT_SECTION\n1\n-1\nEOF\n");
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
