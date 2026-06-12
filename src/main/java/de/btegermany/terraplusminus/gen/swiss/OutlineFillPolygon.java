package de.btegermany.terraplusminus.gen.swiss;

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
import net.buildtheearth.terraminusminus.dataset.vector.geometry.Segment;
import net.buildtheearth.terraminusminus.dataset.vector.geometry.VectorGeometry;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.substitutes.BlockState;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;
import net.buildtheearth.terraminusminus.util.interval.IntervalTree;
import net.daporkchop.lib.common.math.PMath;

/**
 * A lightweight polygon that fills the interior with one block and the boundary with another.
 * This avoids the expensive distance-field computation of {@link net.buildtheearth.terraminusminus.dataset.vector.geometry.polygon.DistancePolygon}
 * and instead detects boundary pixels by checking the 4-neighbourhood of each filled pixel.
 */
public final class OutlineFillPolygon implements VectorGeometry {

    private final String id;
    private final IntervalTree<Segment> segments;
    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;
    private final BlockState outlineBlock;
    private final BlockState interiorBlock;

    public OutlineFillPolygon(
        @NonNull String id,
        @NonNull MultiPolygon polygons,
        @NonNull BlockState outlineBlock,
        @NonNull BlockState interiorBlock
    ) {
        this.id = id;
        this.outlineBlock = outlineBlock;
        this.interiorBlock = interiorBlock;

        // Compute bounds and convert multipolygon to line segments
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        List<Segment> segmentList = new ArrayList<>();

        for (var polygon : polygons.polygons()) {
            for (var point : polygon.outerRing().points()) {
                minX = Math.min(minX, point.lon());
                maxX = Math.max(maxX, point.lon());
                minZ = Math.min(minZ, point.lat());
                maxZ = Math.max(maxZ, point.lat());
            }
            convertToSegments(polygon.outerRing(), segmentList);
            for (var innerRing : polygon.innerRings()) {
                convertToSegments(innerRing, segmentList);
            }
        }

        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.segments = new IntervalTree<>(segmentList);
    }

    @Override
    public void apply(@NonNull CachedChunkData.Builder builder, int chunkX, int chunkZ, @NonNull Bounds2d bounds) {
        int baseX = ChunkPos.cubeToMinBlock(chunkX);
        int baseZ = ChunkPos.cubeToMinBlock(chunkZ);

        // 1. Precompute intersection ranges for an 18×18 area:
        //    x from -1 to 16 (relative to chunk), z from -1 to 16.
        //    This extra 1-pixel margin lets us correctly detect boundary pixels
        //    at chunk edges without falsely assuming they are always on the
        //    polygon perimeter.
        boolean[][] insideMask = new boolean[18][18];
        for (int ex = 0; ex < 18; ex++) {
            double[] points = this.getIntersectionPoints(baseX + ex - 1);
            for (int i = 0; i < points.length; ) {
                int min = clamp(floorI(points[i++]) - baseZ, -1, 17);
                int max = clamp(floorI(points[i++]) - baseZ, -1, 17);
                for (int z = min; z < max; z++) {
                    insideMask[ex][z + 1] = true;
                }
            }
        }

        // 2. Scan the chunk and fill: boundary pixels → outline, interior → clay
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (!insideMask[x + 1][z + 1]) continue;

                // Check 4-neighbourhood in the extended mask
                boolean isBoundary =
                    !insideMask[x][z + 1] ||
                    !insideMask[x + 2][z + 1] ||
                    !insideMask[x + 1][z] ||
                    !insideMask[x + 1][z + 2];

                BlockState block = isBoundary ? this.outlineBlock : this.interiorBlock;
                builder.surfaceBlocks()[x * 16 + z] = block;
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
            if ((segs.size() & 1) == 0) {
                // even count = success
                int size = segs.size();
                if (size == 0) return TerraConstants.EMPTY_DOUBLE_ARRAY;
                double[] arr = new double[size];
                int i = 0;
                for (Segment s : segs) {
                    arr[i++] = PMath.lerp(s.z0(), s.z1(), (s.x0() - center) / (s.x0() - s.x1()));
                }
                Arrays.sort(arr);
                return arr;
            }
            offset = 0.45d + ThreadLocalRandom.current().nextDouble() * 0.1d;
        } while (retries++ < 3);
        return TerraConstants.EMPTY_DOUBLE_ARRAY;
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public double layer() {
        return 0.0d;
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

    @Override
    public int compareTo(VectorGeometry o) {
        int d = Double.compare(this.layer(), o.layer());
        return d == 0 ? this.id().compareTo(o.id()) : d;
    }
}
