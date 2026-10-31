package me.ymid.rutea.app.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import me.ymid.rutea.app.scenario.OperatorContainers;
import me.ymid.rutea.app.scenario.SensitiveSites;
import me.ymid.rutea.geo.RoadGraph;
import me.ymid.rutea.geo.RoadGraphFile;

/**
 * El grafo de Bogotá, sus sitios sensibles y los contenedores de los operadores, leídos la primera vez que se piden:
 * la app arranca aunque falten (en la CI, por ejemplo) y dice qué falta cuando alguien los necesita.
 */
@Component
public class BogotaData {

    public static final class DataMissingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        DataMissingException(String message) {
            super(message);
        }
    }

    record Loaded(RoadGraph graph, Map<Integer, List<OperatorContainers.Container>> real,
            List<SensitiveSites.Site> sites) {
    }

    private final Path graph;
    private final Path sites;
    private final Path containers;
    private Loaded loaded;

    public BogotaData(@Value("${rutea.bogota-graph}") String graph, @Value("${rutea.bogota-sites}") String sites,
            @Value("${rutea.operator-containers}") String containers) {
        this.graph = Path.of(graph);
        this.sites = Path.of(sites);
        this.containers = Path.of(containers);
    }

    /** @throws DataMissingException si falta el grafo, los sitios o el extracto de un operador */
    synchronized Loaded get() {
        if (loaded != null) {
            return loaded;
        }
        List<Path> files = new ArrayList<>(List.of(graph, sites));
        OperatorContainers.LAYERS.forEach(layer -> files.add(containers.resolve(layer.file())));
        for (Path file : files) {
            if (!Files.exists(file)) {
                throw new DataMissingException("Falta " + file.toAbsolutePath().normalize()
                        + ". Se genera con: mvn -pl rutea-geo compile exec:java@bogota,"
                        + " mvn -pl rutea-tools -am compile exec:java@bogota-sensitive-sites y"
                        + " mvn -pl rutea-tools -am compile exec:java@operator-containers (ver data/osm/README.md y data/ase/README.md)");
            }
        }
        try {
            Map<Integer, List<OperatorContainers.Container>> real = new LinkedHashMap<>();
            for (OperatorContainers.Layer layer : OperatorContainers.LAYERS) {
                real.put(layer.area(), OperatorContainers.read(containers.resolve(layer.file())));
            }
            loaded = new Loaded(RoadGraphFile.read(graph).graph(), real, SensitiveSites.read(sites));
            return loaded;
        } catch (IOException e) {
            throw new DataMissingException("No se pudieron leer los datos de Bogotá: " + e.getMessage());
        }
    }
}
