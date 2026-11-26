package de.btegermany.terraplusminus.gen.util;

import lombok.NonNull;
import net.buildtheearth.terraminusminus.TerraConstants;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.LineString;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiPolygon;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.Point;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.Polygon;
import net.buildtheearth.terraminusminus.dataset.vector.geometry.Segment;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;
import net.buildtheearth.terraminusminus.util.interval.IntervalTree;
import net.daporkchop.lib.common.math.PMath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static net.daporkchop.lib.common.math.PMath.clamp;
import static net.daporkchop.lib.common.math.PMath.floorI;

public final class PolygonRasterizer implements Bounds2d {
    private final IntervalTree<Segment> segments;
    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;

    public PolygonRasterizer(@NonNull MultiPolygon polygons) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        List<Segment> segmentList = new ArrayList<>();

        for (Polygon polygon : polygons.polygons()) {
            for (Point point : polygon.outerRing().points()) {
                minX = Math.min(minX, point.lon());
                maxX = Math.max(maxX, point.lon());
                minZ = Math.min(minZ, point.lat());
                maxZ = Math.max(maxZ, point.lat());
            }
            convertToSegments(polygon.outerRing(), segmentList);
            for (LineString innerRing : polygon.innerRings()) {
                convertToSegments(innerRing, segmentList);
            }
        }

        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.segments = new IntervalTree<>(segmentList);
    }

    public boolean @NonNull [] @NonNull [] insideMask(int gridSize, int gridBaseX, int gridBaseZ) {
        boolean[][] insideMask = new boolean[gridSize + 2][gridSize + 2];
        for (int ex = 0; ex < gridSize + 2; ex++) {
            double[] points = this.getIntersectionPoints(gridBaseX + ex - 1);
            for (int i = 0; i + 1 < points.length; ) {
                int min = clamp(floorI(points[i++]) - gridBaseZ, -1, gridSize + 1);
                int max = clamp(floorI(points[i++]) - gridBaseZ, -1, gridSize + 1);
                for (int z = min; z < max; z++) {
                    insideMask[ex][z + 1] = true;
                }
            }
        }
        return insideMask;
    }

    /**
     * uses even-odd rule to check whether the given point is inside the polygon
     */
    public boolean contains(double x, double z) {
        if (x < this.minX || x > this.maxX || z < this.minZ || z > this.maxZ) return false;
        List<Segment> segs = this.segments.getAllIntersecting(x);
        boolean inside = false;
        for (Segment s : segs) {
            if (s.x0() == s.x1()) continue;
            double t = (s.x0() - x) / (s.x0() - s.x1());
            if (t < 0.0d || t >= 1.0d) continue; // half-open, so shared vertices are counted once
            if (PMath.lerp(s.z0(), s.z1(), t) > z) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static void convertToSegments(@NonNull LineString line, @NonNull List<Segment> segments) {
        Point[] points = line.points();
        Point prev = points[0];
        for (int i = 1; i < points.length; i++) {
            Point next = points[i];
            segments.add(new Segment(prev.lon(), prev.lat(), next.lon(), next.lat()));
            prev = next;
        }
    }

    private double[] getIntersectionPoints(int pos) {
        int retries = 0;
        double offset = 0.5d;
        do {
            double center = pos + offset;
            List<Segment> segs = this.segments.getAllIntersecting(center);
            if ((segs.size() & 1) != 0) {
                offset = 0.45d + ThreadLocalRandom.current().nextDouble() * 0.1d;
                continue;
            }

            int size = segs.size();
            if (size == 0) return TerraConstants.EMPTY_DOUBLE_ARRAY;
            double[] arr = new double[size];
            int i = 0;
            for (Segment s : segs) {
                arr[i++] = PMath.lerp(s.z0(), s.z1(), (s.x0() - center) / (s.x0() - s.x1()));
            }
            Arrays.sort(arr);
            return arr;
        } while (retries++ < 3);
        return TerraConstants.EMPTY_DOUBLE_ARRAY;
    }

    @Override
    public double minX() {
        return this.minX;
    }

    @Override
    public double maxX() {
        return this.maxX;
    }

    @Override
    public double minZ() {
        return this.minZ;
    }

    @Override
    public double maxZ() {
        return this.maxZ;
    }
}
