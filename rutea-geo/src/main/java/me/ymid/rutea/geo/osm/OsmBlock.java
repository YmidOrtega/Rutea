package me.ymid.rutea.geo.osm;

import java.util.List;
import java.util.Objects;

/**
 * Contenido de un bloque del PBF. Los nodos van en arreglos paralelos y sin etiquetas: el grafo vial
 * solo necesita su posición. Las coordenadas están en 1e-7 grados, la misma precisión que guarda OSM.
 */
public record OsmBlock(long[] nodeIds, int[] latE7, int[] lonE7, List<OsmWay> ways,
        List<OsmRelation> relations) {

    public OsmBlock {
        Objects.requireNonNull(nodeIds, "nodeIds");
        if (latE7.length != nodeIds.length || lonE7.length != nodeIds.length) {
            throw new IllegalArgumentException("los arreglos de nodos no tienen la misma longitud");
        }
        ways = List.copyOf(ways);
        relations = List.copyOf(relations);
    }

    public int nodeCount() {
        return nodeIds.length;
    }
}
