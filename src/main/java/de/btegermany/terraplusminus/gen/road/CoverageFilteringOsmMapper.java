package de.btegermany.terraplusminus.gen.road;

import net.buildtheearth.terraminusminus.dataset.geojson.Geometry;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.GeometryCollection;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.LineString;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiLineString;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiPoint;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiPolygon;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.Point;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.Polygon;
import net.buildtheearth.terraminusminus.dataset.osm.OSMMapper;
import net.buildtheearth.terraminusminus.dataset.vector.geometry.VectorGeometry;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Drops OSM road features that lie mostly (more than half of their vertices) inside the coverage of a road surface dataset.
 */
public final class CoverageFilteringOsmMapper implements OSMMapper<Geometry> {
    private final OSMMapper<Geometry> delegate;
    private final RoadCoverage coverage;

    public CoverageFilteringOsmMapper(@NonNull OSMMapper<Geometry> delegate, @NonNull RoadCoverage coverage) {
        this.delegate = delegate;
        this.coverage = coverage;
    }

    @Override
    public Collection<VectorGeometry> apply(String id, @NonNull Map<String, String> tags,
                                            @NonNull Geometry originalGeometry, @NonNull Geometry projectedGeometry) {
        String highway = tags.get("highway");
        if (highway != null && this.isCovered(highway, projectedGeometry)) {
            return Collections.emptyList();
        }
        return this.delegate.apply(id, tags, originalGeometry, projectedGeometry);
    }

    private boolean isCovered(@NonNull String highway, @NonNull Geometry projected) {
        Bounds2d bounds = projected.bounds();
        List<Point> vertices = null;
        for (RoadCoverage.Area area : this.coverage.areas()) {
            if (area.keepOsmHighways().contains(highway)) continue;
            if (!area.shape().intersects(bounds)) continue;

            if (vertices == null) {
                vertices = new ArrayList<>();
                collectVertices(projected, vertices);
                if (vertices.isEmpty()) return false;
            }
            int inside = 0;
            for (Point vertex : vertices) {
                if (area.shape().contains(vertex.lon(), vertex.lat())) inside++;
            }
            if (inside * 2 > vertices.size()) return true;
        }
        return false;
    }

    private static void collectVertices(Geometry geometry, List<Point> out) {
        switch (geometry) {
            case Point point -> out.add(point);
            case MultiPoint multiPoint -> out.addAll(List.of(multiPoint.points()));
            case LineString line -> out.addAll(List.of(line.points()));
            case MultiLineString lines -> {
                for (LineString line : lines) out.addAll(List.of(line.points()));
            }
            case Polygon polygon -> out.addAll(List.of(polygon.outerRing().points()));
            case MultiPolygon polygons -> {
                for (Polygon polygon : polygons) out.addAll(List.of(polygon.outerRing().points()));
            }
            case GeometryCollection collection -> {
                for (Geometry child : collection) collectVertices(child, out);
            }
            default -> {
            }
        }
    }
}
