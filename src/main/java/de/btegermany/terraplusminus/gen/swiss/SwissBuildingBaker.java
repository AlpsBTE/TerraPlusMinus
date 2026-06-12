package de.btegermany.terraplusminus.gen.swiss;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

import net.buildtheearth.terraminusminus.dataset.IElementDataset;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.generator.GeneratorDatasets;
import net.buildtheearth.terraminusminus.generator.data.IEarthDataBaker;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;
import net.buildtheearth.terraminusminus.util.bvh.BVH;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;
import net.buildtheearth.terraminusminus.dataset.vector.geometry.VectorGeometry;

/**
 * Bakes highly accurate building outlines from the swissTLM3D dataset into the chunk surface
 */
public class SwissBuildingBaker implements IEarthDataBaker<BVH<VectorGeometry>[]> {
    // WGS84 bounding box covering Switzerland + Liechtenstein
    private static final Bounds2d SWISS_BOUNDS = Bounds2d.of(5.9d, 10.5d, 45.8d, 47.85d);
    public static final String KEY_DATASET_SWISS_BUILDINGS = "swiss_buildings";

    @Override
    public CompletableFuture<BVH<VectorGeometry>[]> requestData(
            ChunkPos pos,
            GeneratorDatasets datasets,
            Bounds2d bounds,
            CornerBoundingBox2d boundsGeo
    ) throws OutOfProjectionBoundsException {
        // Coarse filter: skip chunks far outside bounding box
        if (!SWISS_BOUNDS.intersects(boundsGeo)) return CompletableFuture.completedFuture(null);

        IElementDataset<BVH<VectorGeometry>> dataset = datasets.getCustom(KEY_DATASET_SWISS_BUILDINGS, null);
        if (dataset == null) return CompletableFuture.completedFuture(null);
        return dataset.getAsync(bounds.expand(16.0d).toCornerBB(datasets.projection(), false).toGeo());
    }

    @Override
    public void bake(ChunkPos pos, CachedChunkData.Builder builder, BVH<VectorGeometry>[] regions) {
        if (regions == null) return;

        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        Bounds2d chunkBounds = Bounds2d.of(baseX, baseX + 16, baseZ, baseZ + 16);

        Set<VectorGeometry> elements = new TreeSet<>();
        for (BVH<VectorGeometry> region : regions) {
            region.forEachIntersecting(chunkBounds, elements::add);
        }
        elements.forEach(element -> element.apply(builder, pos.x(), pos.z(), chunkBounds));
    }
}
