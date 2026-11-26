package de.btegermany.terraplusminus.gen.road;

import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.generator.GeneratorDatasets;
import net.buildtheearth.terraminusminus.generator.data.IEarthDataBaker;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.substitutes.BlockState;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;
import net.buildtheearth.terraminusminus.util.bvh.BVH;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class RoadSurfaceBaker implements IEarthDataBaker<@Nullable List<BVH<RoadPolygon>>> {
    private final MultiRoadSurfaceDataset dataset;

    public RoadSurfaceBaker(@NonNull MultiRoadSurfaceDataset dataset) {
        this.dataset = dataset;
    }

    @Override
    public CompletableFuture<@Nullable List<BVH<RoadPolygon>>> requestData(
            ChunkPos pos,
            GeneratorDatasets datasets,
            Bounds2d bounds,
            CornerBoundingBox2d boundsGeo
    ) throws OutOfProjectionBoundsException {
        return this.dataset.getAsync(boundsGeo);
    }

    @Override
    public void bake(ChunkPos pos, CachedChunkData.Builder builder, @Nullable List<BVH<RoadPolygon>> regions) {
        if (regions == null) return;

        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        Bounds2d chunkBounds = Bounds2d.of(baseX, baseX + 16, baseZ, baseZ + 16);

        List<RoadPolygon> polygons = new ArrayList<>();
        for (BVH<RoadPolygon> region : regions) {
            region.forEachIntersecting(chunkBounds, polygons::add);
        }
        if (polygons.isEmpty()) return;
        polygons.sort(RoadPolygon.PAINT_ORDER);

        RoadPolygon[] grid = new RoadPolygon[16 * 16];
        for (RoadPolygon polygon : polygons) {
            polygon.fill(grid, 16, baseX, baseZ);
        }

        BlockState[] surfaceBlocks = builder.surfaceBlocks();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                RoadPolygon polygon = grid[x * 16 + z];
                if (polygon == null) continue;
                surfaceBlocks[x * 16 + z] = polygon.material().pick(baseX + x, baseZ + z);
            }
        }
    }
}
