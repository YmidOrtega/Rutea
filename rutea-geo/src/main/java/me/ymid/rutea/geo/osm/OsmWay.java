package me.ymid.rutea.geo.osm;

import java.util.Map;
import java.util.Objects;

public record OsmWay(long id, long[] nodeIds, Map<String, String> tags) {

    public OsmWay {
        Objects.requireNonNull(nodeIds, "nodeIds");
        Objects.requireNonNull(tags, "tags");
    }
}
