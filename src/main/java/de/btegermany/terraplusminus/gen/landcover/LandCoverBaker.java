package de.btegermany.terraplusminus.gen.landcover;

import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.generator.GeneratorDatasets;
import net.buildtheearth.terraminusminus.generator.data.IEarthDataBaker;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;
import net.buildtheearth.terraminusminus.util.bvh.BVH;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

// TODO: move to T--?
/**
 * Bakes a 16x16 array of canonical {@link LandCoverType} ordinals for each chunk.
 * When any surface pattern requests an edge falloff, the baker also requests a margin
 * around the chunk and stores the distance of each column to the nearest landcover edge.
 */
public final class LandCoverBaker implements IEarthDataBaker<LandCoverBaker.BakedLandCover> {
    public static final byte FALLBACK_ORDINAL = (byte) LandCoverType.UNKNOWN.ordinal();

    private final MultiLandCoverDataset dataset;
    private final int maxEdgeFalloff;

    public LandCoverBaker(@NonNull MultiLandCoverDataset dataset, int maxEdgeFalloff) {
        this.dataset = dataset;
        this.maxEdgeFalloff = Math.max(0, maxEdgeFalloff);
    }

    @Override
    public CompletableFuture<BakedLandCover> requestData(
            ChunkPos pos,
            GeneratorDatasets datasets,
            Bounds2d bounds,
            CornerBoundingBox2d boundsGeo
    ) throws OutOfProjectionBoundsException {
        CornerBoundingBox2d expandedBoundsGeo = boundsGeo;
        if (this.maxEdgeFalloff > 0) {
            try {
                expandedBoundsGeo = bounds.expand(this.maxEdgeFalloff)
                        .toCornerBB(datasets.projection(), false)
                        .toGeo();
            } catch (OutOfProjectionBoundsException ignored) {
                // near projection boundaries we fall back to the unexpanded bounds
            }
        }
        return this.dataset.getAsync(expandedBoundsGeo).thenApply(results -> this.bakeLandCover(results, pos));
    }

    @Override
    public void bake(ChunkPos pos, CachedChunkData.Builder builder, BakedLandCover landCover) {
        builder.putCustom(MultiLandCoverDataset.KEY, landCover != null ? landCover : fallbackBaked());
    }

    private BakedLandCover bakeLandCover(@Nullable List<MultiLandCoverDataset.DatasetResult> results, ChunkPos pos) {
        if (results == null) return fallbackBaked();

        int gridSize = 16 + this.maxEdgeFalloff * 2;
        int gridBaseX = ChunkPos.cubeToMinBlock(pos.x()) - this.maxEdgeFalloff;
        int gridBaseZ = ChunkPos.cubeToMinBlock(pos.z()) - this.maxEdgeFalloff;

        LandCoverType[] working = new LandCoverType[gridSize * gridSize];
        Bounds2d chunkBounds = Bounds2d.of(gridBaseX, gridBaseX + gridSize, gridBaseZ, gridBaseZ + gridSize);

        for (MultiLandCoverDataset.DatasetResult result : results) {
            if (result.polygons() == null) continue;
            for (BVH<LandCoverPolygon> bvh : result.polygons()) {
                bvh.forEachIntersecting(chunkBounds, polygon -> polygon.fill(working, gridSize, gridBaseX, gridBaseZ));
            }
        }

        byte[] outTypes = new byte[16 * 16];
        byte[] outDistances = this.maxEdgeFalloff > 0 ? new byte[16 * 16] : null;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                LandCoverType type = working[(x + this.maxEdgeFalloff) * gridSize + (z + this.maxEdgeFalloff)];
                outTypes[x * 16 + z] = (byte) (type != null ? type.ordinal() : LandCoverType.UNKNOWN.ordinal());
            }
        }

        if (outDistances != null) {
            computeEdgeDistances(working, gridSize, this.maxEdgeFalloff, outDistances, this.maxEdgeFalloff);
        }

        return new BakedLandCover(outTypes, outDistances, this.maxEdgeFalloff);
    }

    private static void computeEdgeDistances(
            LandCoverType @NonNull [] expanded, int gridSize, int offset,
            byte @NonNull [] distances, int maxDistance
    ) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                LandCoverType centerType = expanded[(x + offset) * gridSize + (z + offset)];
                int minDist = maxDistance;
                for (int dx = -maxDistance; dx <= maxDistance && minDist > 0; dx++) {
                    int ex = x + offset + dx;
                    if (ex < 0 || ex >= gridSize) continue;
                    for (int dz = -maxDistance; dz <= maxDistance && minDist > 0; dz++) {
                        int ez = z + offset + dz;
                        if (ez < 0 || ez >= gridSize) continue;
                        if (expanded[ex * gridSize + ez] != centerType) {
                            int dist = Math.max(Math.abs(dx), Math.abs(dz));
                            if (dist < minDist) {
                                minDist = dist;
                            }
                        }
                    }
                }
                distances[x * 16 + z] = (byte) minDist;
            }
        }
    }

    private static BakedLandCover fallbackBaked() {
        byte[] arr = new byte[16 * 16];
        Arrays.fill(arr, FALLBACK_ORDINAL);
        return new BakedLandCover(arr, null, 0);
    }

    /**
     * The baked landcover data for a chunk: the central 16x16 types and, when edge falloff
     * is enabled, the Chebyshev distance of each column to the nearest landcover edge.
     */
    public record BakedLandCover(byte @NonNull [] types, byte @Nullable [] edgeDistances, int maxEdgeFalloff) {
    }
}
