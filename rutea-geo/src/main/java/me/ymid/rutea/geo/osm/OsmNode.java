package me.ymid.rutea.geo.osm;

import java.util.Map;
import java.util.Objects;

public record OsmNode(long id, int latE7, int lonE7, Map<String, String> tags) {

    public OsmNode {
        Objects.requireNonNull(tags, "tags");
    }
}
