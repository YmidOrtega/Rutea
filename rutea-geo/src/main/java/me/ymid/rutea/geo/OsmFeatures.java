package me.ymid.rutea.geo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import me.ymid.rutea.geo.geometry.BoundingBox;
import me.ymid.rutea.geo.osm.OsmNode;
import me.ymid.rutea.geo.osm.OsmWay;
import me.ymid.rutea.geo.osm.PbfReader;
import me.ymid.rutea.geo.osm.PbfReader.Element;

/**
 * Lugares de OSM con ciertas etiquetas dentro de un rectángulo: un colegio puede estar mapeado como un nodo o
 * como el polígono de su predio, y los dos cuentan. Qué etiquetas interesan lo decide quien llama.
 */
public final class OsmFeatures {

    /**
     * Un lugar con sus puntos: uno si es un nodo; los vértices del polígono si es una vía. Para saber si algo
     * queda "frente a" un predio se mide al vértice más cercano, no al centro del predio.
     */
    public record Feature(String osmType, long osmId, Map<String, String> tags, int[] latE7, int[] lonE7) {

        public Feature {
            Objects.requireNonNull(tags, "tags");
            if (latE7.length == 0 || latE7.length != lonE7.length) {
                throw new IllegalArgumentException("un lugar necesita al menos un punto");
            }
        }

        public String name() {
            return tags.getOrDefault("name", "");
        }
    }

    private OsmFeatures() {
    }

    public static List<Feature> read(Path pbf, BoundingBox box, Predicate<Map<String, String>> wanted)
            throws IOException {
        List<Feature> features = new ArrayList<>();
        List<OsmWay> ways = new ArrayList<>();
        PbfReader.read(pbf, EnumSet.of(Element.TAGGED_NODES, Element.WAYS), block -> {
            for (OsmNode node : block.taggedNodes()) {
                if (wanted.test(node.tags()) && box.contains(node.latE7(), node.lonE7())) {
                    features.add(new Feature("node", node.id(), node.tags(), new int[] {node.latE7()},
                            new int[] {node.lonE7()}));
                }
            }
            for (OsmWay way : block.ways()) {
                if (wanted.test(way.tags())) {
                    ways.add(way);
                }
            }
        });
        if (ways.isEmpty()) {
            return features;
        }

        long[] needed = ways.stream().flatMapToLong(way -> Arrays.stream(way.nodeIds())).sorted().distinct().toArray();
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
        for (OsmWay way : ways) {
            long[] ids = way.nodeIds();
            // El último nodo de un polígono cerrado repite el primero: se omite.
            int count = ids.length > 1 && ids[0] == ids[ids.length - 1] ? ids.length - 1 : ids.length;
            int[] wayLat = new int[count];
            int[] wayLon = new int[count];
            int kept = 0;
            boolean inside = false;
            for (int i = 0; i < count; i++) {
                int index = Arrays.binarySearch(needed, ids[i]);
                if (found[index]) {
                    wayLat[kept] = lat[index];
                    wayLon[kept] = lon[index];
                    inside |= box.contains(wayLat[kept], wayLon[kept]);
                    kept++;
                }
            }
            if (inside) {
                features.add(new Feature("way", way.id(), way.tags(), Arrays.copyOf(wayLat, kept),
                        Arrays.copyOf(wayLon, kept)));
            }
        }
        return features;
    }
}
