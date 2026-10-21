package me.ymid.rutea.solver.cvrplib;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.ymid.rutea.solver.CvrplibData;
import me.ymid.rutea.solver.Instance;

class CvrplibReaderTest {

    private static final String TINY = """
            NAME : tiny
            TYPE : CVRP
            DIMENSION : 3
            EDGE_WEIGHT_TYPE : EUC_2D
            CAPACITY : 8
            NODE_COORD_SECTION
            1 0 0
            2 3 4
            3 6 8
            DEMAND_SECTION
            1 0
            2 5
            3 5
            DEPOT_SECTION
            1
            -1
            EOF
            """;

    @TempDir
    Path dir;

    @Test
    void readsInstanceWithTabsAndWindowsLineEndings() throws IOException {
        Instance instance = CvrplibReader.readInstance(CvrplibData.dir().resolve("X-n101-k25.vrp"));

        assertThat(instance.name()).isEqualTo("X-n101-k25");
        assertThat(instance.size()).isEqualTo(101);
        assertThat(instance.capacity()).isEqualTo(206);
        assertThat(instance.demand(0)).isZero();
        assertThat(instance.demand(1)).isEqualTo(38);
        // Nodos 1 (365,689) y 2 (146,180) del archivo: √307042 = 554,1 → 554
        assertThat(instance.distance(0, 1)).isEqualTo(554);
    }

    @Test
    void readsBestKnownSolution() throws IOException {
        BestKnownSolution bks = CvrplibReader.readSolution(CvrplibData.dir().resolve("X-n101-k25.sol"));

        assertThat(bks.cost()).isEqualTo(27591);
        assertThat(bks.solution().routeCount()).isEqualTo(26);
        assertThat(bks.solution().route(0)).containsExactly(31, 46, 35);
    }

    @Test
    void readsInstanceWithSpacesAndUnixLineEndings() throws IOException {
        Instance instance = CvrplibReader.readInstance(write("tiny.vrp", TINY));

        assertThat(instance.customerCount()).isEqualTo(2);
        assertThat(instance.distance(1, 2)).isEqualTo(5);
        assertThat(instance.distance(0, 2)).isEqualTo(10);
    }

    @Test
    void reportsFileAndLineOfMalformedEntry() throws IOException {
        Path file = write("tiny.vrp", TINY.replace("2 3 4", "2 3"));

        assertThatThrownBy(() -> CvrplibReader.readInstance(file))
                .isInstanceOf(CvrplibFormatException.class)
                .hasMessage("tiny.vrp:8: se esperaban 3 valores, llegaron 2: 2 3");
    }

    @Test
    void rejectsUnsupportedEdgeWeightType() throws IOException {
        Path file = write("tiny.vrp", TINY.replace("EUC_2D", "GEO"));

        assertThatThrownBy(() -> CvrplibReader.readInstance(file))
                .isInstanceOf(CvrplibFormatException.class)
                .hasMessage("tiny.vrp:4: EDGE_WEIGHT_TYPE 'GEO' no soportado; solo EUC_2D o EXPLICIT");
    }

    /** Matriz asimétrica de 3 nodos: ir de 1 a 2 cuesta 5 y volver, 7. */
    private static final String EXPLICIT = """
            NAME : asym
            TYPE : CVRP
            DIMENSION : 3
            EDGE_WEIGHT_TYPE : EXPLICIT
            EDGE_WEIGHT_FORMAT : FULL_MATRIX
            CAPACITY : 10
            EDGE_WEIGHT_SECTION
            0 4 6
            3 0 5
            9 7
            0
            DEMAND_SECTION
            1 0
            2 4
            3 6
            DEPOT_SECTION
             1
             -1
            EOF
            """;

    @Test
    void readsAnAsymmetricFullMatrixSplitAcrossLines() throws IOException {
        Instance instance = CvrplibReader.readInstance(write("asym.vrp", EXPLICIT));

        assertThat(instance.distance(1, 2)).isEqualTo(5);
        assertThat(instance.distance(2, 1)).isEqualTo(7);
        assertThat(instance.distance(2, 0)).isEqualTo(9);
        assertThat(instance.distances().isSymmetric()).isFalse();
        assertThat(instance.demand(2)).isEqualTo(6);
    }

    @Test
    void rejectsAnIncompleteFullMatrix() throws IOException {
        Path file = write("asym.vrp", EXPLICIT.replace("9 7\n0\n", "9 7\n"));

        assertThatThrownBy(() -> CvrplibReader.readInstance(file))
                .hasMessage("asym.vrp: EDGE_WEIGHT_SECTION tiene 8 de 3² valores");
    }

    @Test
    void rejectsOtherExplicitFormats() throws IOException {
        Path file = write("asym.vrp", EXPLICIT.replace("FULL_MATRIX", "UPPER_ROW"));

        assertThatThrownBy(() -> CvrplibReader.readInstance(file))
                .hasMessage("asym.vrp:5: EDGE_WEIGHT_FORMAT 'UPPER_ROW' no soportado; solo FULL_MATRIX");
    }

    @Test
    void writerRoundTripsAnAsymmetricInstance() throws IOException {
        Instance original = CvrplibReader.readInstance(write("asym.vrp", EXPLICIT));
        Path copy = dir.resolve("copia.vrp");

        CvrplibWriter.writeExplicit(copy, original, "© colaboradores de OpenStreetMap (ODbL)");
        Instance read = CvrplibReader.readInstance(copy);

        assertThat(read.name()).isEqualTo("asym");
        assertThat(read.capacity()).isEqualTo(10);
        for (int i = 0; i < 3; i++) {
            assertThat(read.demand(i)).isEqualTo(original.demand(i));
            for (int j = 0; j < 3; j++) {
                assertThat(read.distance(i, j)).isEqualTo(original.distance(i, j));
            }
        }
        assertThat(Files.readString(copy)).contains("COMMENT : © colaboradores de OpenStreetMap (ODbL)");
    }

    @Test
    void rejectsMissingDemand() throws IOException {
        Path file = write("tiny.vrp", TINY.replace("3 5\n", ""));

        assertThatThrownBy(() -> CvrplibReader.readInstance(file))
                .isInstanceOf(CvrplibFormatException.class)
                .hasMessage("tiny.vrp: falta demanda del nodo 3");
    }

    @Test
    void rejectsSolutionWithoutCost() throws IOException {
        Path file = write("tiny.sol", "Route #1: 1 2\n");

        assertThatThrownBy(() -> CvrplibReader.readSolution(file))
                .isInstanceOf(CvrplibFormatException.class)
                .hasMessage("tiny.sol: falta la línea 'Cost'");
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }
}
