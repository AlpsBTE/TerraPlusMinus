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
 * Bakes a 16x16 array of canonical {@link LandCoverType} ordinals for each chunk
 */
public final class LandCoverBaker implements IEarthDataBaker<byte[]> {
    public static final byte FALLBACK_ORDINAL = (byte) LandCoverType.UNKNOWN.ordinal();

    private final MultiLandCoverDataset dataset;

    public LandCoverBaker(@NonNull MultiLandCoverDataset dataset) {
        this.dataset = dataset;
    }

    @Override
    public CompletableFuture<byte[]> requestData(
            ChunkPos pos,
            GeneratorDatasets datasets,
            Bounds2d bounds,
            CornerBoundingBox2d boundsGeo
    ) throws OutOfProjectionBoundsException {
        return this.dataset.getAsync(boundsGeo).thenApply(results -> this.bakeLandCover(results, pos));
    }

    @Override
    public void bake(ChunkPos pos, CachedChunkData.Builder builder, byte[] landCover) {
        builder.putCustom(MultiLandCoverDataset.KEY, landCover != null ? landCover : fallbackArray());
    }

    private byte[] bakeLandCover(@Nullable List<MultiLandCoverDataset.DatasetResult> results, ChunkPos pos) {
        if (results == null) return fallbackArray();

        LandCoverType[] working = new LandCoverType[16 * 16];
        Bounds2d chunkBounds = Bounds2d.of(
                ChunkPos.cubeToMinBlock(pos.x()),
                ChunkPos.cubeToMinBlock(pos.x()) + 16,
                ChunkPos.cubeToMinBlock(pos.z()),
                ChunkPos.cubeToMinBlock(pos.z()) + 16
        );

        for (MultiLandCoverDataset.DatasetResult result : results) {
            if (result.polygons() == null) continue;
            for (BVH<LandCoverPolygon> bvh : result.polygons()) {
                bvh.forEachIntersecting(chunkBounds, polygon -> polygon.fill(working, pos.x(), pos.z()));
            }
        }

        byte[] out = new byte[16 * 16];
        for (int i = 0; i < 16 * 16; i++) {
            out[i] = (byte) (working[i] != null ? working[i].ordinal() : LandCoverType.UNKNOWN.ordinal());
        }
        return out;
    }

    private static byte[] fallbackArray() {
        byte[] arr = new byte[16 * 16];
        Arrays.fill(arr, FALLBACK_ORDINAL);
        return arr;
    }
}
