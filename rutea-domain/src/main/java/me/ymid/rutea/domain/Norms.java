package me.ymid.rutea.domain;

import java.net.URI;

final class Norms {

    static final String DECREE_1077_2015 = "Decreto 1077 de 2015";
    static final URI DECREE_1077_2015_SOURCE =
            URI.create("https://www.funcionpublica.gov.co/eva/gestornormativo/norma.php?i=77216");

    /** Sustituye el capítulo 2.3.2.5 del Decreto 1077 de 2015 (aprovechamiento). */
    static final String DECREE_1381_2024 = "Decreto 1077 de 2015 (texto del Decreto 1381 de 2024)";
    static final URI DECREE_1381_2024_SOURCE =
            URI.create("https://normas.cra.gov.co/gestor/docs/decreto_1381_2024.htm");

    static final String RESOLUTION_2184_2019 = "Resolución 2184 de 2019";
    static final URI RESOLUTION_2184_2019_SOURCE =
            URI.create("https://normas.cra.gov.co/gestor/docs/resolucion_minambienteds_2184_2019.htm");

    private Norms() {
    }
}
