package de.btegermany.terraplusminus.gen.building.outline;

import de.btegermany.terraplusminus.gen.util.PolygonRasterizer;
import lombok.NonNull;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiPolygon;
import net.buildtheearth.terraminusminus.dataset.vector.geometry.VectorGeometry;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.substitutes.BlockState;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;

public final class OutlineFillPolygon implements VectorGeometry {
    private final String id;
    private final double layer;
    private final PolygonRasterizer rasterizer;
    private final BlockState outlineBlock;
    private final BlockState interiorBlock;

    public OutlineFillPolygon(
            @NonNull String id,
            double layer,
            @NonNull MultiPolygon polygons,
            @NonNull BlockState outlineBlock,
            @NonNull BlockState interiorBlock
    ) {
        this.id = id;
        this.layer = layer;
        this.outlineBlock = outlineBlock;
        this.interiorBlock = interiorBlock;
        this.rasterizer = new PolygonRasterizer(polygons);
    }

    @Override
    public void apply(@NonNull CachedChunkData.Builder builder, int chunkX, int chunkZ, @NonNull Bounds2d bounds) {
        int baseX = ChunkPos.cubeToMinBlock(chunkX);
        int baseZ = ChunkPos.cubeToMinBlock(chunkZ);

        boolean[][] insideMask = this.rasterizer.insideMask(16, baseX, baseZ);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (!insideMask[x + 1][z + 1]) continue;

                boolean isBoundary =
                        !insideMask[x][z + 1] ||
                        !insideMask[x + 2][z + 1] ||
                        !insideMask[x + 1][z] ||
                        !insideMask[x + 1][z + 2];

                builder.surfaceBlocks()[x * 16 + z] = isBoundary ? this.outlineBlock : this.interiorBlock;
            }
        }
    }

    @Override
    public String id() {
        return this.id;
    }

    @Override
    public double layer() {
        return this.layer;
    }

    @Override
    public double minX() {
        return this.rasterizer.minX();
    }

    @Override
    public double maxX() {
        return this.rasterizer.maxX();
    }

    @Override
    public double minZ() {
        return this.rasterizer.minZ();
    }

    @Override
    public double maxZ() {
        return this.rasterizer.maxZ();
    }
}
