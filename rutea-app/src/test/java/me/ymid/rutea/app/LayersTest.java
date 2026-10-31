package me.ymid.rutea.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class LayersTest {

    private static final Path SOURCES = Path.of("src/main/java/me/ymid/rutea/app");

    private static final Pattern APP_IMPORT = Pattern.compile("^import (?:static )?me\\.ymid\\.rutea\\.app\\.(\\w+)\\.",
            Pattern.MULTILINE);

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "scenario", Set.of(),
            "planning", Set.of("scenario"),
            "operations", Set.of("planning", "scenario"),
            "persistence", Set.of(),
            "application", Set.of("operations", "planning", "scenario", "persistence"),
            "web", Set.of("application", "planning"));

    private static final Set<String> WITHOUT_SPRING = Set.of("scenario", "planning", "operations");

    @Test
    void eachLayerOnlyImportsTheLayersBelowIt() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            String layer = SOURCES.relativize(file).getName(0).toString();
            assertThat(ALLOWED).as("capa de %s", file).containsKey(layer);
            Matcher imported = APP_IMPORT.matcher(Files.readString(file));
            while (imported.find()) {
                String target = imported.group(1);
                if (!target.equals(layer) && !ALLOWED.get(layer).contains(target)) {
                    violations.add(SOURCES.relativize(file) + " → " + target);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void theModelDoesNotKnowSpring() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            String layer = SOURCES.relativize(file).getName(0).toString();
            if (WITHOUT_SPRING.contains(layer) && Files.readString(file).contains("import org.springframework.")) {
                violations.add(SOURCES.relativize(file).toString());
            }
        }
        assertThat(violations).isEmpty();
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> SOURCES.relativize(file).getNameCount() > 1)
                    .toList();
        }
    }
}
