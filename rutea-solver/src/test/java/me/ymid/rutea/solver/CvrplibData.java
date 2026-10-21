package me.ymid.rutea.solver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Surefire pasa la ruta en la propiedad rutea.cvrplib.dir. */
public final class CvrplibData {

    public static final String DIR_PROPERTY = "rutea.cvrplib.dir";

    private CvrplibData() {
    }

    public static Path dir() {
        String dir = Objects.requireNonNull(System.getProperty(DIR_PROPERTY),
                DIR_PROPERTY + " no está definida; corre los tests con Maven");
        Path path = Path.of(dir);
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("No existe el directorio de instancias: " + path);
        }
        return path;
    }
}
