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
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;

import me.ymid.rutea.geo.geometry.BoundingBox;
import me.ymid.rutea.geo.geometry.Haversine;
import me.ymid.rutea.geo.geometry.Polygon;
import me.ymid.rutea.geo.osm.OsmBlock;
import me.ymid.rutea.geo.osm.OsmRelation;
import me.ymid.rutea.geo.osm.OsmRelation.Member;
import me.ymid.rutea.geo.osm.OsmRelation.MemberType;
import me.ymid.rutea.geo.osm.OsmWay;
import me.ymid.rutea.geo.osm.PbfReader;
import me.ymid.rutea.geo.osm.PbfReader.Element;

/**
 * Construye el {@link RoadGraph} de un {@link AreaSpec} a partir de un extracto PBF, en cuatro
 * pasadas: relaciones de los límites, vías, nodos de los límites y nodos de las vías candidatas.
 * Solo se conserva la mayor componente fuertemente conexa, para que desde cualquier vértice se pueda
 * llegar a cualquier otro y volver.
 */
public final class RoadGraphBuilder {

    /** Cifras de la construcción, para reportar y para revisar que el recorte tiene sentido. */
    public record Report(int candidateWays, int includedWays, int verticesBeforePruning, int edgesBeforePruning,
            int localityVerticesBeforePruning, int vertices, int edges, int shapes, long streetMeters,
            int localityVertices, int localityCenterVertex, Map<String, Integer> siteVertices) {

        public Report {
            siteVertices = Map.copyOf(siteVertices);
        }
    }

    public record Result(RoadGraph graph, Report report) {
    }

    /** {@code privateAccess}: solo entra si toca la zona de un sitio. */
    private record Candidate(long[] nodeIds, RoadClass roadClass, Direction direction, int kmPerHour,
            boolean privateAccess) {
    }

    private record Piece(int[] nodes, Candidate way) {
    }

    private RoadGraphBuilder() {
    }

    public static Result build(Path pbf, AreaSpec spec) throws IOException {
        Map<Long, List<Long>> boundaryWays = boundaryWays(pbf, spec);

        Set<Long> wantedWays = new HashSet<>();
        boundaryWays.values().forEach(wantedWays::addAll);
        Map<Long, long[]> wayNodes = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        PbfReader.read(pbf, EnumSet.of(Element.WAYS), block -> {
            for (OsmWay way : block.ways()) {
                if (wantedWays.contains(way.id())) {
                    wayNodes.put(way.id(), way.nodeIds());
                }
                Optional<RoadClass> roadClass = RoadClass.ofAnyAccess(way.tags());
                Direction direction = Direction.of(way.tags());
                if (roadClass.isPresent() && direction != Direction.NONE && way.nodeIds().length >= 2) {
                    candidates.add(new Candidate(way.nodeIds(), roadClass.get(), direction,
                            roadClass.get().kmPerHour(way.tags()), RoadClass.isPrivate(way.tags())));
                }
            }
        });
        for (long id : wantedWays) {
            if (!wayNodes.containsKey(id)) {
                throw new IOException(pbf.getFileName() + ": falta la vía " + id + " de un límite");
            }
        }

        long[] boundaryNodeIds = LongStream.concat(
                wayNodes.values().stream().flatMapToLong(Arrays::stream),
                spec.sites().stream().mapToLong(AreaSpec.Site::osmNode)).sorted().distinct().toArray();
        Nodes boundaryNodes = Nodes.read(pbf, boundaryNodeIds, null);
        Polygon locality = polygon(boundaryWays.get(spec.localityRelation()), wayNodes, boundaryNodes, "localidad");
        Polygon city = polygon(boundaryWays.get(spec.cityRelation()), wayNodes, boundaryNodes, "ciudad");
        List<BoundingBox> siteZones = new ArrayList<>();
        BoundingBox region = city.bounds().union(locality.bounds());
        for (AreaSpec.Site site : spec.sites()) {
            int index = boundaryNodes.indexOf(site.osmNode());
            if (index < 0) {
                throw new IOException(pbf.getFileName() + ": no está el nodo " + site.osmNode() + " del sitio "
                        + site.name());
            }
            int lat = boundaryNodes.latE7[index];
            int lon = boundaryNodes.lonE7[index];
            BoundingBox zone = new BoundingBox(lat, lon, lat, lon).expand(site.marginMeters());
            siteZones.add(zone);
            region = region.union(zone);
        }

        long[] candidateNodeIds = candidates.stream().flatMapToLong(c -> Arrays.stream(c.nodeIds()))
                .sorted().distinct().toArray();
        Nodes nodes = Nodes.read(pbf, candidateNodeIds, region);

        List<Piece> pieces = new ArrayList<>();
        int includedWays = 0;
        for (Candidate candidate : candidates) {
            if (includes(candidate, nodes, locality, city, siteZones)) {
                includedWays++;
                splitIntoPieces(candidate, nodes, pieces);
            }
        }
        return assemble(pieces, nodes, locality, spec.sites(), candidates.size(), includedWays);
    }

    /** Vías exteriores e interiores de cada límite, por id de relación. */
    private static Map<Long, List<Long>> boundaryWays(Path pbf, AreaSpec spec) throws IOException {
        Map<Long, List<Long>> ways = new HashMap<>();
        PbfReader.read(pbf, EnumSet.of(Element.RELATIONS), block -> {
            for (OsmRelation relation : block.relations()) {
                if (relation.id() == spec.localityRelation() || relation.id() == spec.cityRelation()) {
                    List<Long> members = new ArrayList<>();
                    for (Member member : relation.members()) {
                        if (member.type() == MemberType.WAY && (member.role().equals("outer")
                                || member.role().equals("inner") || member.role().isEmpty())) {
                            members.add(member.ref());
                        }
                    }
                    ways.put(relation.id(), members);
                }
            }
        });
        for (long id : new long[] {spec.localityRelation(), spec.cityRelation()}) {
            if (!ways.containsKey(id)) {
                throw new IOException(pbf.getFileName() + ": no está la relación " + id);
            }
        }
        return ways;
    }

    private static Polygon polygon(List<Long> wayIds, Map<Long, long[]> wayNodes, Nodes nodes, String what)
            throws IOException {
        List<long[]> ways = new ArrayList<>();
        for (long id : wayIds) {
            ways.add(wayNodes.get(id));
        }
        List<int[]> lats = new ArrayList<>();
        List<int[]> lons = new ArrayList<>();
        for (long[] ring : Polygon.assembleRings(ways)) {
            int[] lat = new int[ring.length];
            int[] lon = new int[ring.length];
            for (int i = 0; i < ring.length; i++) {
                int index = nodes.indexOf(ring[i]);
                if (index < 0) {
                    throw new IOException("límite de " + what + ": falta el nodo " + ring[i]);
                }
                lat[i] = nodes.latE7[index];
                lon[i] = nodes.lonE7[index];
            }
            lats.add(lat);
            lons.add(lon);
        }
        return new Polygon(lats, lons);
    }

    private static boolean includes(Candidate way, Nodes nodes, Polygon locality, Polygon city,
            List<BoundingBox> siteZones) {
        boolean street = way.roadClass().isPublicStreet() && !way.privateAccess();
        boolean arterial = way.roadClass().isArterial() && !way.privateAccess();
        for (long id : way.nodeIds()) {
            int index = nodes.indexOf(id);
            if (index < 0) {
                continue;
            }
            int lat = nodes.latE7[index];
            int lon = nodes.lonE7[index];
            if ((street && locality.contains(lat, lon)) || (arterial && city.contains(lat, lon))) {
                return true;
            }
            for (BoundingBox zone : siteZones) {
                if (zone.contains(lat, lon)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Parte la vía donde le faltan nodos (salen de la región) y quita nodos repetidos seguidos. */
    private static void splitIntoPieces(Candidate way, Nodes nodes, List<Piece> pieces) {
        IntList run = new IntList(way.nodeIds().length);
        for (long id : way.nodeIds()) {
            int index = nodes.indexOf(id);
            if (index < 0) {
                addPiece(run, way, pieces);
                run = new IntList(way.nodeIds().length);
            } else if (run.size() == 0 || run.get(run.size() - 1) != index) {
                run.add(index);
            }
        }
        addPiece(run, way, pieces);
    }

    private static void addPiece(IntList run, Candidate way, List<Piece> pieces) {
        if (run.size() >= 2) {
            pieces.add(new Piece(run.toArray(), way));
        }
    }

    private static Result assemble(List<Piece> pieces, Nodes nodes, Polygon locality, List<AreaSpec.Site> sites,
            int candidateWays, int includedWays) throws IOException {
        // Un nodo es vértice si es extremo de una vía, lo comparten dos tramos o es un sitio.
        int[] usage = new int[nodes.size()];
        boolean[] endpoint = new boolean[nodes.size()];
        for (Piece piece : pieces) {
            for (int node : piece.nodes()) {
                usage[node]++;
            }
            endpoint[piece.nodes()[0]] = true;
            endpoint[piece.nodes()[piece.nodes().length - 1]] = true;
        }
        int[] siteNodes = new int[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            siteNodes[i] = nodes.indexOf(sites.get(i).osmNode());
            if (siteNodes[i] < 0 || usage[siteNodes[i]] == 0) {
                throw new IOException("el sitio " + sites.get(i).name() + " (nodo " + sites.get(i).osmNode()
                        + ") no está sobre ninguna vía del recorte");
            }
            endpoint[siteNodes[i]] = true;
        }
        int[] vertexOf = new int[nodes.size()];
        int vertexCount = 0;
        int localityVerticesBefore = 0;
        for (int node = 0; node < nodes.size(); node++) {
            vertexOf[node] = endpoint[node] || usage[node] >= 2 ? vertexCount++ : -1;
            if (vertexOf[node] >= 0 && locality.contains(nodes.latE7[node], nodes.lonE7[node])) {
                localityVerticesBefore++;
            }
        }

        IntList source = new IntList(pieces.size() * 2);
        IntList target = new IntList(pieces.size() * 2);
        IntList meters = new IntList(pieces.size() * 2);
        IntList seconds = new IntList(pieces.size() * 2);
        IntList roadClass = new IntList(pieces.size() * 2);
        IntList shape = new IntList(pieces.size() * 2);
        List<int[]> shapes = new ArrayList<>();
        for (Piece piece : pieces) {
            int[] path = piece.nodes();
            int start = 0;
            for (int i = 1; i < path.length; i++) {
                if (vertexOf[path[i]] < 0) {
                    continue;
                }
                int from = vertexOf[path[start]];
                int to = vertexOf[path[i]];
                if (from != to) {
                    int[] points = Arrays.copyOfRange(path, start, i + 1);
                    int length = (int) Math.round(length(points, nodes));
                    int travel = (int) Math.round(length * 3.6 / piece.way().kmPerHour());
                    int shapeId = shapes.size();
                    shapes.add(points);
                    if (piece.way().direction().forward()) {
                        addEdge(source, target, meters, seconds, roadClass, shape, from, to, length, travel,
                                piece.way().roadClass(), shapeId);
                    }
                    if (piece.way().direction().backward()) {
                        addEdge(source, target, meters, seconds, roadClass, shape, to, from, length, travel,
                                piece.way().roadClass(), ~shapeId);
                    }
                }
                start = i;
            }
        }
        int edgesBefore = source.size();

        int[] firstEdge = new int[vertexCount + 1];
        int[] order = csrOrder(source, vertexCount, firstEdge);
        int[] targets = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            targets[i] = target.get(order[i]);
        }
        int[] component = StrongComponents.largest(vertexCount, firstEdge, targets);

        // Se renumeran los vértices de la componente en el orden original de los nodos (por id de OSM).
        int[] newVertex = new int[vertexCount];
        Arrays.fill(newVertex, -1);
        int kept = 0;
        long[] osmIds = new long[component.length];
        int[] lat = new int[component.length];
        int[] lon = new int[component.length];
        boolean[] inComponent = new boolean[vertexCount];
        for (int v : component) {
            inComponent[v] = true;
        }
        int[] nodeOfVertex = new int[vertexCount];
        for (int node = 0; node < nodes.size(); node++) {
            if (vertexOf[node] >= 0) {
                nodeOfVertex[vertexOf[node]] = node;
            }
        }
        int localityVertices = 0;
        for (int v = 0; v < vertexCount; v++) {
            if (inComponent[v]) {
                int node = nodeOfVertex[v];
                osmIds[kept] = nodes.ids[node];
                lat[kept] = nodes.latE7[node];
                lon[kept] = nodes.lonE7[node];
                if (locality.contains(lat[kept], lon[kept])) {
                    localityVertices++;
                }
                newVertex[v] = kept++;
            }
        }

        IntList keptEdges = new IntList(order.length);
        int[] newFirstEdge = new int[kept + 1];
        for (int v = 0; v < vertexCount; v++) {
            if (!inComponent[v]) {
                continue;
            }
            for (int e = firstEdge[v]; e < firstEdge[v + 1]; e++) {
                if (inComponent[targets[e]]) {
                    keptEdges.add(order[e]);
                    newFirstEdge[newVertex[v] + 1]++;
                }
            }
        }
        for (int v = 0; v < kept; v++) {
            newFirstEdge[v + 1] += newFirstEdge[v];
        }

        int[] newShapeId = new int[shapes.size()];
        Arrays.fill(newShapeId, -1);
        IntList firstShapePoint = new IntList(shapes.size() + 1);
        firstShapePoint.add(0);
        IntList shapeLat = new IntList(shapes.size() * 4);
        IntList shapeLon = new IntList(shapes.size() * 4);
        List<Boolean> shapeInLocality = new ArrayList<>();
        long streetMeters = 0;
        int edgeCount = keptEdges.size();
        int[] edgeTarget = new int[edgeCount];
        int[] edgeMeters = new int[edgeCount];
        int[] edgeSeconds = new int[edgeCount];
        byte[] edgeRoadClass = new byte[edgeCount];
        int[] edgeShape = new int[edgeCount];
        for (int e = 0; e < edgeCount; e++) {
            int original = keptEdges.get(e);
            edgeTarget[e] = newVertex[target.get(original)];
            edgeMeters[e] = meters.get(original);
            edgeSeconds[e] = seconds.get(original);
            edgeRoadClass[e] = (byte) roadClass.get(original);
            int oldShape = shape.get(original);
            int base = oldShape >= 0 ? oldShape : ~oldShape;
            if (newShapeId[base] < 0) {
                newShapeId[base] = firstShapePoint.size() - 1;
                int[] points = shapes.get(base);
                for (int node : points) {
                    shapeLat.add(nodes.latE7[node]);
                    shapeLon.add(nodes.lonE7[node]);
                }
                firstShapePoint.add(shapeLat.size());
                int middle = points[points.length / 2];
                shapeInLocality.add(locality.contains(nodes.latE7[middle], nodes.lonE7[middle]));
                streetMeters += edgeMeters[e];
            }
            edgeShape[e] = oldShape >= 0 ? newShapeId[base] : ~newShapeId[base];
        }

        RoadGraph graph = new RoadGraph(osmIds, lat, lon, newFirstEdge, edgeTarget, edgeMeters, edgeSeconds,
                edgeRoadClass, edgeShape, firstShapePoint.toArray(), shapeLat.toArray(), shapeLon.toArray(),
                toBooleans(shapeInLocality));
        Map<String, Integer> siteVertices = new HashMap<>();
        for (int i = 0; i < sites.size(); i++) {
            int vertex = newVertex[vertexOf[siteNodes[i]]];
            if (vertex < 0) {
                throw new IOException("el sitio " + sites.get(i).name() + " (nodo " + sites.get(i).osmNode()
                        + ") quedó fuera de la componente fuertemente conexa: no se puede ir y volver");
            }
            siteVertices.put(sites.get(i).name(), vertex);
        }
        int[] localityCenter = center(locality);
        Report report = new Report(candidateWays, includedWays, vertexCount, edgesBefore, localityVerticesBefore,
                kept, edgeCount, graph.shapeCount(), streetMeters, localityVertices,
                graph.nearestVertex(localityCenter[0], localityCenter[1]), siteVertices);
        return new Result(graph, report);
    }

    private static boolean[] toBooleans(List<Boolean> values) {
        boolean[] array = new boolean[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    private static void addEdge(IntList source, IntList target, IntList meters, IntList seconds, IntList roadClass,
            IntList shape, int from, int to, int length, int travel, RoadClass type, int shapeId) {
        source.add(from);
        target.add(to);
        meters.add(length);
        seconds.add(travel);
        roadClass.add(type.ordinal());
        shape.add(shapeId);
    }

    /** Orden estable de los arcos por vértice de origen; llena {@code firstEdge}. */
    private static int[] csrOrder(IntList source, int vertexCount, int[] firstEdge) {
        for (int e = 0; e < source.size(); e++) {
            firstEdge[source.get(e) + 1]++;
        }
        for (int v = 0; v < vertexCount; v++) {
            firstEdge[v + 1] += firstEdge[v];
        }
        int[] next = Arrays.copyOf(firstEdge, vertexCount);
        int[] order = new int[source.size()];
        for (int e = 0; e < source.size(); e++) {
            order[next[source.get(e)]++] = e;
        }
        return order;
    }

    private static double length(int[] points, Nodes nodes) {
        double meters = 0;
        for (int i = 1; i < points.length; i++) {
            meters += Haversine.meters(nodes.latE7[points[i - 1]], nodes.lonE7[points[i - 1]],
                    nodes.latE7[points[i]], nodes.lonE7[points[i]]);
        }
        return meters;
    }

    private static int[] center(Polygon polygon) {
        BoundingBox box = polygon.bounds();
        return new int[] {(int) (((long) box.minLatE7() + box.maxLatE7()) / 2),
                (int) (((long) box.minLonE7() + box.maxLonE7()) / 2)};
    }

    /** Coordenadas de un conjunto de nodos, ordenados por id para buscarlos por bisección. */
    private static final class Nodes {

        private final long[] ids;
        private final int[] latE7;
        private final int[] lonE7;

        private Nodes(long[] ids, int[] latE7, int[] lonE7) {
            this.ids = ids;
            this.latE7 = latE7;
            this.lonE7 = lonE7;
        }

        /** Lee los nodos de {@code wanted} (ordenado) que caen dentro de {@code region}, o todos si es null. */
        static Nodes read(Path pbf, long[] wanted, BoundingBox region) throws IOException {
            long[] ids = new long[wanted.length];
            int[] lat = new int[wanted.length];
            int[] lon = new int[wanted.length];
            int[] count = {0};
            PbfReader.read(pbf, EnumSet.of(Element.NODES), (OsmBlock block) -> {
                long[] blockIds = block.nodeIds();
                for (int i = 0; i < blockIds.length; i++) {
                    if (Arrays.binarySearch(wanted, blockIds[i]) >= 0
                            && (region == null || region.contains(block.latE7()[i], block.lonE7()[i]))) {
                        ids[count[0]] = blockIds[i];
                        lat[count[0]] = block.latE7()[i];
                        lon[count[0]] = block.lonE7()[i];
                        count[0]++;
                    }
                }
            });
            int n = count[0];
            Nodes nodes = new Nodes(Arrays.copyOf(ids, n), Arrays.copyOf(lat, n), Arrays.copyOf(lon, n));
            nodes.sortById();
            return nodes;
        }

        /** Geofabrik ordena los nodos por id; si un extracto no lo hace, se ordenan aquí. */
        private void sortById() {
            for (int i = 1; i < ids.length; i++) {
                if (ids[i - 1] >= ids[i]) {
                    Integer[] order = new Integer[ids.length];
                    for (int k = 0; k < order.length; k++) {
                        order[k] = k;
                    }
                    Arrays.sort(order, (a, b) -> Long.compare(ids[a], ids[b]));
                    long[] sortedIds = new long[ids.length];
                    int[] sortedLat = new int[ids.length];
                    int[] sortedLon = new int[ids.length];
                    for (int k = 0; k < order.length; k++) {
                        sortedIds[k] = ids[order[k]];
                        sortedLat[k] = latE7[order[k]];
                        sortedLon[k] = lonE7[order[k]];
                    }
                    System.arraycopy(sortedIds, 0, ids, 0, ids.length);
                    System.arraycopy(sortedLat, 0, latE7, 0, ids.length);
                    System.arraycopy(sortedLon, 0, lonE7, 0, ids.length);
                    return;
                }
            }
        }

        int size() {
            return ids.length;
        }

        int indexOf(long id) {
            int index = Arrays.binarySearch(ids, id);
            return index >= 0 ? index : -1;
        }
    }
}
