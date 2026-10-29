package me.ymid.rutea.geo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.ymid.rutea.geo.geometry.Polygon;
import me.ymid.rutea.geo.osm.OsmRelation;
import me.ymid.rutea.geo.osm.OsmRelation.Member;
import me.ymid.rutea.geo.osm.OsmRelation.MemberType;
import me.ymid.rutea.geo.osm.OsmWay;
import me.ymid.rutea.geo.osm.PbfReader;
import me.ymid.rutea.geo.osm.PbfReader.Element;

/** Los límites que una relación de OSM agrupa como subáreas: las UPZ de una localidad de Bogotá, por ejemplo. */
public final class OsmBoundaries {

    /** Un límite con sus anillos en 1e-7 grados, cada uno sin repetir el punto que lo cierra. */
    public record Boundary(long osmId, Map<String, String> tags, List<int[]> ringsLatE7, List<int[]> ringsLonE7) {

        public Boundary {
            tags = Map.copyOf(tags);
            ringsLatE7 = List.copyOf(ringsLatE7);
            ringsLonE7 = List.copyOf(ringsLonE7);
        }

        public String name() {
            return tags.getOrDefault("name", "");
        }

        public String ref() {
            return tags.getOrDefault("ref", "");
        }

        public Polygon polygon() {
            return new Polygon(ringsLatE7, ringsLonE7);
        }
    }

    private record Relation(Map<String, String> tags, List<Long> ways, List<Long> subareas) {
    }

    private OsmBoundaries() {
    }

    /**
     * Las subáreas de la relación {@code parent}, en el orden en que la relación las lista.
     *
     * @throws IOException si falta la relación, una subárea, una vía o un nodo, o un anillo no cierra
     */
    public static List<Boundary> subareas(Path pbf, long parent) throws IOException {
        Map<Long, Relation> relations = relations(pbf);
        Relation group = relations.get(parent);
        if (group == null) {
            throw new IOException(pbf.getFileName() + ": no está la relación " + parent);
        }
        if (group.subareas().isEmpty()) {
            throw new IOException(pbf.getFileName() + ": la relación " + parent + " no tiene subáreas");
        }
        for (long id : group.subareas()) {
            if (!relations.containsKey(id)) {
                throw new IOException(pbf.getFileName() + ": falta la subárea " + id + " de la relación " + parent);
            }
        }
        return boundaries(pbf, relations, group.subareas());
    }

    /**
     * Los límites de las relaciones {@code ids}, en ese orden: las localidades de Bogotá, por ejemplo, que la relación
     * del Distrito no lista como subáreas.
     *
     * @throws IOException si falta una relación, una vía o un nodo, o un anillo no cierra
     */
    public static List<Boundary> of(Path pbf, List<Long> ids) throws IOException {
        return boundaries(pbf, relations(pbf), ids);
    }

    private static Map<Long, Relation> relations(Path pbf) throws IOException {
        Map<Long, Relation> relations = new HashMap<>();
        PbfReader.read(pbf, EnumSet.of(Element.RELATIONS), block -> {
            for (OsmRelation relation : block.relations()) {
                if (!"boundary".equals(relation.tags().get("type"))) {
                    continue;
                }
                List<Long> ways = new ArrayList<>();
                List<Long> subareas = new ArrayList<>();
                for (Member member : relation.members()) {
                    if (member.type() == MemberType.WAY
                            && (member.role().equals("outer") || member.role().equals("inner") || member.role().isEmpty())) {
                        ways.add(member.ref());
                    } else if (member.type() == MemberType.RELATION && member.role().equals("subarea")) {
                        subareas.add(member.ref());
                    }
                }
                relations.put(relation.id(), new Relation(relation.tags(), ways, subareas));
            }
        });
        return relations;
    }

    private static List<Boundary> boundaries(Path pbf, Map<Long, Relation> relations, List<Long> ids)
            throws IOException {
        Set<Long> wanted = new HashSet<>();
        for (long id : ids) {
            Relation boundary = relations.get(id);
            if (boundary == null) {
                throw new IOException(pbf.getFileName() + ": no está el límite " + id);
            }
            wanted.addAll(boundary.ways());
        }

        Map<Long, long[]> wayNodes = new HashMap<>();
        PbfReader.read(pbf, EnumSet.of(Element.WAYS), block -> {
            for (OsmWay way : block.ways()) {
                if (wanted.contains(way.id())) {
                    wayNodes.put(way.id(), way.nodeIds());
                }
            }
        });
        for (long id : wanted) {
            if (!wayNodes.containsKey(id)) {
                throw new IOException(pbf.getFileName() + ": falta la vía " + id + " de un límite");
            }
        }

        long[] needed = wayNodes.values().stream().flatMapToLong(Arrays::stream).sorted().distinct().toArray();
        int[] lat = new int[needed.length];
        int[] lon = new int[needed.length];
        boolean[] found = new boolean[needed.length];
        PbfReader.read(pbf, EnumSet.of(Element.NODES), block -> {
            for (int i = 0; i < block.nodeCount(); i++) {
                int index = Arrays.binarySearch(needed, block.nodeIds()[i]);
                if (index >= 0) {
                    lat[index] = block.latE7()[i];
                    lon[index] = block.lonE7()[i];
                    found[index] = true;
                }
            }
        });

        List<Boundary> boundaries = new ArrayList<>();
        for (long id : ids) {
            Relation subarea = relations.get(id);
            List<long[]> ways = subarea.ways().stream().map(wayNodes::get).toList();
            List<int[]> ringLat = new ArrayList<>();
            List<int[]> ringLon = new ArrayList<>();
            List<long[]> rings;
            try {
                rings = Polygon.assembleRings(ways);
            } catch (IllegalArgumentException e) {
                throw new IOException("límite " + id + " (" + subarea.tags().get("name") + "): " + e.getMessage(), e);
            }
            for (long[] ring : rings) {
                int n = ring.length - 1;
                int[] ringLatE7 = new int[n];
                int[] ringLonE7 = new int[n];
                for (int i = 0; i < n; i++) {
                    int index = Arrays.binarySearch(needed, ring[i]);
                    if (!found[index]) {
                        throw new IOException("límite " + id + ": falta el nodo " + ring[i]);
                    }
                    ringLatE7[i] = lat[index];
                    ringLonE7[i] = lon[index];
                }
                ringLat.add(ringLatE7);
                ringLon.add(ringLonE7);
            }
            boundaries.add(new Boundary(id, subarea.tags(), ringLat, ringLon));
        }
        return boundaries;
    }
}
