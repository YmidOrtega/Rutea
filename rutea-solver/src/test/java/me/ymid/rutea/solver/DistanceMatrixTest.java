package me.ymid.rutea.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class DistanceMatrixTest {

    @ParameterizedTest(name = "({0},{1})-({2},{3}) → {4}")
    @CsvSource({
            "0, 0, 3, 4, 5",  // exacta
            "0, 0, 1, 1, 1",  // 1,414 baja
            "0, 0, 2, 3, 4",  // 3,606 sube
    })
    void roundsToNearestIntegerLikeTsplib(double x1, double y1, double x2, double y2, int expected) {
        DistanceMatrix matrix = DistanceMatrix.euclidean(new double[] {x1, x2}, new double[] {y1, y2});

        assertThat(matrix.get(0, 1)).isEqualTo(expected);
    }

    @Test
    void isSymmetricWithZeroDiagonal() {
        DistanceMatrix matrix = DistanceMatrix.euclidean(
                new double[] {0, 3, 10, -4}, new double[] {0, 4, 7, 2});

        for (int i = 0; i < matrix.size(); i++) {
            assertThat(matrix.get(i, i)).isZero();
            for (int j = 0; j < matrix.size(); j++) {
                assertThat(matrix.get(i, j)).isEqualTo(matrix.get(j, i));
            }
        }
    }

    @Test
    void nintRoundsHalfUp() {
        assertThat(DistanceMatrix.nint(2.5)).isEqualTo(3);
        assertThat(DistanceMatrix.nint(2.4999)).isEqualTo(2);
    }

    @Test
    void keepsPrecomputedAsymmetricDistances() {
        DistanceMatrix matrix = DistanceMatrix.of(new int[][] {{0, 7}, {9, 0}});

        assertThat(matrix.get(0, 1)).isEqualTo(7);
        assertThat(matrix.get(1, 0)).isEqualTo(9);
    }

    @Test
    void rejectsNonSquareNegativeOrNonZeroDiagonal() {
        assertThatIllegalArgumentException().isThrownBy(() -> DistanceMatrix.of(new int[][] {{0, 1}, {1}}));
        assertThatIllegalArgumentException().isThrownBy(() -> DistanceMatrix.of(new int[][] {{0, -1}, {1, 0}}));
        assertThatIllegalArgumentException().isThrownBy(() -> DistanceMatrix.of(new int[][] {{3, 1}, {1, 0}}));
    }

    @Test
    void rejectsCoordinatesOfDifferentLength() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DistanceMatrix.euclidean(new double[3], new double[2]));
    }
}
