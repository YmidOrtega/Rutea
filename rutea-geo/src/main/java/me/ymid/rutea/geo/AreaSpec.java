package me.ymid.rutea.geo;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Qué parte de la red vial entra al grafo, por ids de OSM.
 *
 * @param localityRelation límite de la localidad: entran todas sus calles públicas
 * @param cityRelation límite de la ciudad: entran sus vías principales, para llegar a los sitios
 * @param sites lugares fuera del recorte normal (base de los camiones, sitio de disposición) que el grafo
 *     tiene que alcanzar: alrededor de cada uno entran todas las vías, y su nodo queda como vértice
 */
public record AreaSpec(long localityRelation, long cityRelation, List<Site> sites) {

    /**
     * @param name nombre corto con el que se busca el sitio en el grafo
     * @param osmNode nodo de OSM donde está el sitio, sobre una vía
     * @param marginMeters radio alrededor del nodo en el que entran todas las vías
     */
    public record Site(String name, long osmNode, int marginMeters) {

        public Site {
            Objects.requireNonNull(name, "name");
            if (name.isBlank() || marginMeters < 0) {
                throw new IllegalArgumentException("sitio inválido: '" + name + "', margen " + marginMeters);
            }
        }
    }

    public static final String DEPOT = "base";
    public static final String DISPOSAL = "relleno";

    /**
     * Suba (relación 2214884) y Bogotá D. C. (relación 1387968), en el extracto de {@code data/osm}.
     *
     * <ul>
     *   <li>Base de Torca: Avenida Calle 17 # 124-81, Fontibón, según la inspección de la
     *       Superservicios de marzo de 2026. Se toma la esquina de la Av. Calle 17 con la Carrera 124
     *       (nodo 865490722); la base queda a unos 81 m de ella.
     *   <li>Relleno Sanitario Doña Juana: portería noreste, nodo 1013564592 ({@code barrier=swing_gate}),
     *       sobre el borde del polígono del relleno (vía 229080866) y del lado de la ciudad.
     * </ul>
     */
    public static final AreaSpec SUBA = new AreaSpec(2214884, 1387968, List.of(
            new Site(DEPOT, 865490722, 500),
            new Site(DISPOSAL, 1013564592, 2000)));

    /**
     * Las calles públicas de todo Bogotá D. C. (relación 1387968), con los sitios de {@link #SUBA} y las bases de las
     * ASE 1 a 4 (dirección y fuente en {@code docs/datos.md}). Cada base está mapeada en OSM; su nodo es donde la vía
     * del predio sale a la calle pública o, si no hay vía mapeada, el punto de la calle más cercano al edificio:
     *
     * <ul>
     *   <li>ASE 1, Arzobispo (vía 458791813): salida a la Carrera 60, frente al número 15-07.
     *   <li>ASE 2, Tunjuelo (vía 881770228, Carrera 62 # 19-04): salida del patio a la Carrera 62 con Calle 20.
     *   <li>ASE 3, Fucha (vía 700784521): salida del patio a la Calle 59C Sur.
     *   <li>ASE 4, Salitre (vía 1210674921): calzada sur de la Av. Calle 63, a unos 31 m del edificio.
     * </ul>
     *
     * Los cuatro nodos están sobre calles públicas, que ya entran al grafo, así que van con margen 0: de lo privado
     * entra solo la vía que pasa por el nodo, la de acceso al predio. La localidad de cada calle no queda en el grafo:
     * se asigna por polígono donde se necesita.
     */
    public static final AreaSpec BOGOTA = new AreaSpec(1387968, 1387968, List.of(
            SUBA.sites().get(0), SUBA.sites().get(1),
            new Site(depot(1), 8483428972L, 0),
            new Site(depot(2), 3868073372L, 0),
            new Site(depot(3), 6580796244L, 0),
            new Site(depot(4), 5769034872L, 0)));

    /** El sitio de la base de una ASE en {@link #BOGOTA}; la del ASE 5 es {@link #DEPOT}, como en {@link #SUBA}. */
    public static String depot(int area) {
        return area == 5 ? DEPOT : DEPOT + "-ase" + area;
    }

    public AreaSpec {
        sites = List.copyOf(sites);
        Set<String> names = new HashSet<>();
        for (Site site : sites) {
            if (!names.add(site.name())) {
                throw new IllegalArgumentException("sitio repetido: " + site.name());
            }
        }
    }
}
