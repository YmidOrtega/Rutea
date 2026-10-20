package me.ymid.rutea.geo.osm;

import java.util.Map;
import java.util.Objects;

/** Vía de OSM: la secuencia de nodos que la forman, en orden, y sus etiquetas. */
public record OsmWay(long id, long[] nodeIds, Map<String, String> tags) {

    public OsmWay {
        Objects.requireNonNull(nodeIds, "nodeIds");
        Objects.requireNonNull(tags, "tags");
    }
}
