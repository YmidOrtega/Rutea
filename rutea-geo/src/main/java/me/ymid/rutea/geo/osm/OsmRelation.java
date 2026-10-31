package me.ymid.rutea.geo.osm;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record OsmRelation(long id, List<Member> members, Map<String, String> tags) {

    public enum MemberType { NODE, WAY, RELATION }

    public record Member(MemberType type, long ref, String role) {

        public Member {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(role, "role");
        }
    }

    public OsmRelation {
        members = List.copyOf(members);
        Objects.requireNonNull(tags, "tags");
    }
}
