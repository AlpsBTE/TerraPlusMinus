package de.btegermany.terraplusminus.gen.landcover;

import static net.daporkchop.lib.common.math.PMath.clamp;
import static net.daporkchop.lib.common.math.PMath.floorI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import lombok.NonNull;
import net.buildtheearth.terraminusminus.TerraConstants;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.LineString;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiPolygon;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.Point;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.Polygon;
import net.buildtheearth.terraminusminus.dataset.vector.geometry.Segment;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;
import net.buildtheearth.terraminusminus.util.interval.IntervalTree;
import net.daporkchop.lib.common.math.PMath;
import org.jspecify.annotations.Nullable;

//TODO: move to T--
public final class LandCoverPolygon implements Bounds2d {
    private final String id;
    private final LandCoverType type;
    private final IntervalTree<Segment> segments;
    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;

    public LandCoverPolygon(@NonNull String id, @NonNull LandCoverType type, @NonNull MultiPolygon polygons) {
        this.id = id;
        this.type = type;

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

    /**
     * Fills the given land-cover array for the chunk at {@code chunkX}/{@code chunkZ}
     */
    public void fill(@Nullable LandCoverType @NonNull [] landCover, int chunkX, int chunkZ) {
        int baseX = ChunkPos.cubeToMinBlock(chunkX);
        int baseZ = ChunkPos.cubeToMinBlock(chunkZ);
        this.fill(landCover, 16, baseX, baseZ);
    }

    /**
     * Fills a square region of arbitrary size starting at the given projected block coordinates.
     * Only {@code null} entries are overwritten, so higher-priority polygons keep their values.
     */
    public void fill(@Nullable LandCoverType @NonNull [] landCover, int gridSize, int gridBaseX, int gridBaseZ) {
        // Compute a (gridSize+2)² mask (one-cell padding on each side) so boundary detection is easy.
        boolean[][] insideMask = new boolean[gridSize + 2][gridSize + 2];
        for (int ex = 0; ex < gridSize + 2; ex++) {
            double[] points = this.getIntersectionPoints(gridBaseX + ex - 1);
            for (int i = 0; i < points.length; ) {
                int min = clamp(floorI(points[i++]) - gridBaseZ, -1, gridSize + 1);
                int max = clamp(floorI(points[i++]) - gridBaseZ, -1, gridSize + 1);
                for (int z = min; z < max; z++) {
                    insideMask[ex][z + 1] = true;
                }
            }
        }

        for (int x = 0; x < gridSize; x++) {
            for (int z = 0; z < gridSize; z++) {
                if (insideMask[x + 1][z + 1] && landCover[x * gridSize + z] == null) {
                    landCover[x * gridSize + z] = this.type;
                }
            }
        }
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

    public @NonNull String id() {
        return this.id;
    }

    public @NonNull LandCoverType type() {
        return this.type;
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
