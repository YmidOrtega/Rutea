package me.ymid.rutea.solver.cvrplib;

import java.util.Objects;

import me.ymid.rutea.solver.Solution;

/**
 * Mejor solución conocida (BKS) publicada para una instancia.
 *
 * @param solution las rutas de la BKS
 * @param cost     el costo publicado en el archivo {@code .sol}
 */
public record BestKnownSolution(Solution solution, long cost) {

    public BestKnownSolution {
        Objects.requireNonNull(solution, "solution");
    }
}
