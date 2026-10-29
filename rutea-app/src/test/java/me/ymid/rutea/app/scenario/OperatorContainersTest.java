package me.ymid.rutea.app.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.app.scenario.OperatorContainers.Container;
import me.ymid.rutea.app.scenario.OperatorContainers.Layer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OperatorContainersTest {

    private static final Layer LAYER = new Layer(2, "https://example.org/FeatureServer/0", "1=1", "CODIGO",
            "CAPACIDAD", "LOCALIDAD", "MICRORRUTA");

    @TempDir
    Path dir;

    private static JsonNode json(String text) {
        return JsonMapper.builder().build().readTree(text);
    }

    @Test
    void readsCodeLocationLitersAndRouteFromAQueryPage() {
        List<Container> containers = OperatorContainers.parse(LAYER, json("""
                {"features":[
                  {"attributes":{"CODIGO":"46598","CAPACIDAD":2200,"LOCALIDAD":"BOSA","MICRORRUTA":"1113"},
                   "geometry":{"x":-74.1965455,"y":4.6324569}},
                  {"attributes":{"CODIGO":46599,"CAPACIDAD":"2200 ","LOCALIDAD":"CIUDAD BOLIVAR","MICRORRUTA":null},
                   "geometry":{"x":-74.172476,"y":4.5770992}}]}"""));

        assertThat(containers).containsExactly(
                new Container("46598", 46_324_569, -741_965_455, 2_200, "BOSA", "1113"),
                new Container("46599", 45_770_992, -741_724_760, 2_200, "CIUDAD BOLIVAR", ""));
    }

    @Test
    void saysWhatIsWrongWithTheLayer() {
        assertThatThrownBy(() -> OperatorContainers.parse(LAYER, json("{\"error\":{\"code\":400}}")))
                .hasMessageContaining("ASE 2").hasMessageContaining("400");
        assertThatThrownBy(() -> OperatorContainers.parse(LAYER, json("""
                {"features":[{"attributes":{"CODIGO":"7","CAPACIDAD":2200,"LOCALIDAD":"BOSA","MICRORRUTA":"1"},
                  "geometry":null}]}"""))).hasMessageContaining("el contenedor 7 no tiene ubicación");
        assertThatThrownBy(() -> OperatorContainers.parse(LAYER, json("""
                {"features":[{"attributes":{"CODIGO":"7"},"geometry":{"x":0,"y":0}}]}""")))
                .hasMessageContaining("no trae el campo CAPACIDAD");
    }

    @Test
    void writesTheSameBytesWhateverTheOrderAndReadsThemBack() throws IOException {
        Container a = new Container("A2", 46_000_000, -741_000_000, 1_100, "USME", "01420");
        Container b = new Container("A1", 47_000_000, -740_000_000, 2_200, "USAQUEN, NORTE", "01451");
        Path once = dir.resolve("once.csv");
        Path again = dir.resolve("again.csv");

        OperatorContainers.write(once, List.of(a, b));
        OperatorContainers.write(again, List.of(b, a));

        assertThat(Files.readAllBytes(once)).isEqualTo(Files.readAllBytes(again));
        assertThat(OperatorContainers.read(once)).containsExactly(
                new Container("A1", 47_000_000, -740_000_000, 2_200, "USAQUEN  NORTE", "01451"), a);
    }

    @Test
    void everyLayerKeepsOnlyNonRecyclableContainersOfAnAreaThatPublishesThem() {
        assertThat(OperatorContainers.LAYERS).extracting(Layer::area).containsExactly(1, 2, 4);
        assertThat(OperatorContainers.LAYERS).allSatisfy(layer -> {
            assertThat(layer.where()).contains("APROVECHAB='N'");
            assertThat(layer.url()).startsWith("https://").endsWith("/FeatureServer/0");
        });
        assertThat(OperatorContainers.LAYERS.get(1).file()).isEqualTo("ase2-contenedores-negros.csv");
    }
}
