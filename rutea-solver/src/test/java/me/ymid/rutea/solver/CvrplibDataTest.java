package me.ymid.rutea.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class CvrplibDataTest {

    @Test
    void everyInstanceHasItsBestKnownSolution() throws IOException {
        List<Path> instances;
        try (Stream<Path> files = Files.list(CvrplibData.dir())) {
            instances = files.filter(p -> p.toString().endsWith(".vrp")).toList();
        }

        assertThat(instances).isNotEmpty();
        assertThat(instances).allSatisfy(vrp -> {
            String name = vrp.getFileName().toString().replace(".vrp", ".sol");
            assertThat(vrp.resolveSibling(name)).exists();
        });
    }
}
