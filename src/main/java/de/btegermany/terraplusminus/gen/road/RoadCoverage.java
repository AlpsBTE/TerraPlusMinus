package de.btegermany.terraplusminus.gen.road;

import de.btegermany.terraplusminus.gen.building.config.DatasetExtent;
import de.btegermany.terraplusminus.gen.util.PolygonRasterizer;
import net.buildtheearth.terraminusminus.dataset.geojson.GeoJson;
import net.buildtheearth.terraminusminus.dataset.geojson.GeoJsonObject;
import net.buildtheearth.terraminusminus.dataset.geojson.Geometry;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.GeometryCollection;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.LineString;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiPolygon;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.Point;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.Polygon;
import net.buildtheearth.terraminusminus.dataset.geojson.object.Feature;
import net.buildtheearth.terraminusminus.dataset.geojson.object.FeatureCollection;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RoadCoverage {
    // number of points per edge used to approximate a bounds rectangle in projected space
    private static final int BOUNDS_EDGE_POINTS = 32;

    private final List<Area> areas;

    private RoadCoverage(@NonNull List<Area> areas) {
        this.areas = List.copyOf(areas);
    }

    public boolean isEmpty() {
        return this.areas.isEmpty();
    }

    public @NonNull List<Area> areas() {
        return this.areas;
    }

    public static @NonNull RoadCoverage load(
            @NonNull List<RoadSurfaceConfig.DatasetConfig> datasets,
            @NonNull GeographicProjection projection,
            @NonNull ComponentLogger logger
    ) {
        List<Area> areas = new ArrayList<>();
        for (RoadSurfaceConfig.DatasetConfig dataset : datasets) {
            if (!dataset.replaceOsmRoads()) continue;
            try {
                MultiPolygon geo;
                String source;
                if (Files.exists(dataset.coverageFile())) {
                    geo = readCoverage(dataset);
                    source = dataset.coverageFile().toString();
                } else {
                    geo = boundsPolygon(dataset.bounds());
                    source = "configured bounds";
                }
                if (geo.polygons().length == 0) {
                    logger.warn("Road surface dataset '{}': coverage '{}' contains no polygons, OSM roads are not replaced", dataset.id(), source);
                    continue;
                }
                MultiPolygon projected = projectLenient(geo, projection);
                if (projected.polygons().length == 0) continue;
                areas.add(new Area(dataset.id(), new PolygonRasterizer(projected), dataset.keepOsmHighways()));
                logger.info("Road surface dataset '{}' replaces OSM roads within its coverage ({})", dataset.id(), source);
            } catch (IOException | RuntimeException e) {
                logger.warn("Road surface dataset '{}': failed to load coverage, OSM roads are not replaced: {}", dataset.id(), e.getMessage());
            }
        }
        return new RoadCoverage(areas);
    }

    private static MultiPolygon readCoverage(RoadSurfaceConfig.DatasetConfig dataset) throws IOException {
        GeoJsonObject root;
        try (Reader reader = Files.newBufferedReader(dataset.coverageFile(), StandardCharsets.UTF_8)) {
            root = GeoJson.parse(reader);
        }
        List<Polygon> polygons = new ArrayList<>();
        collectPolygons(root, polygons);
        return new MultiPolygon(polygons.toArray(new Polygon[0]));
    }

    private static void collectPolygons(GeoJsonObject object, List<Polygon> out) {
        if (object instanceof FeatureCollection collection) {
            for (Feature feature : collection) collectPolygons(feature, out);
        } else if (object instanceof Feature feature) {
            if (feature.geometry() != null) collectPolygons(feature.geometry(), out);
        } else if (object instanceof Polygon polygon) {
            out.add(polygon);
        } else if (object instanceof MultiPolygon multiPolygon) {
            out.addAll(List.of(multiPolygon.polygons()));
        } else if (object instanceof GeometryCollection collection) {
            for (Geometry geometry : collection) collectPolygons(geometry, out);
        }
    }

    private static MultiPolygon boundsPolygon(DatasetExtent bounds) {
        Point[] ring = new Point[BOUNDS_EDGE_POINTS * 4 + 1];
        int i = 0;
        for (int k = 0; k < BOUNDS_EDGE_POINTS; k++) {
            double t = (double) k / BOUNDS_EDGE_POINTS;
            ring[i++] = new Point(lerp(bounds.minLon(), bounds.maxLon(), t), bounds.minLat());
        }
        for (int k = 0; k < BOUNDS_EDGE_POINTS; k++) {
            double t = (double) k / BOUNDS_EDGE_POINTS;
            ring[i++] = new Point(bounds.maxLon(), lerp(bounds.minLat(), bounds.maxLat(), t));
        }
        for (int k = 0; k < BOUNDS_EDGE_POINTS; k++) {
            double t = (double) k / BOUNDS_EDGE_POINTS;
            ring[i++] = new Point(lerp(bounds.maxLon(), bounds.minLon(), t), bounds.maxLat());
        }
        for (int k = 0; k < BOUNDS_EDGE_POINTS; k++) {
            double t = (double) k / BOUNDS_EDGE_POINTS;
            ring[i++] = new Point(bounds.minLon(), lerp(bounds.maxLat(), bounds.minLat(), t));
        }
        ring[i] = ring[0];
        return new MultiPolygon(new Polygon[]{new Polygon(new LineString(ring), new LineString[0])});
    }

    private static MultiPolygon projectLenient(MultiPolygon geo, GeographicProjection projection) {
        List<Polygon> projected = new ArrayList<>();
        for (Polygon polygon : geo.polygons()) {
            try {
                projected.add(polygon.project(projection::fromGeo));
            } catch (OutOfProjectionBoundsException ignored) {
                // outside the projection
            }
        }
        return new MultiPolygon(projected.toArray(new Polygon[0]));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public record Area(@NonNull String datasetId, @NonNull PolygonRasterizer shape, @NonNull Set<String> keepOsmHighways) {
    }
}
