package de.btegermany.terraplusminus.gen.landcover;

import de.btegermany.terraplusminus.gen.util.PolygonRasterizer;
import lombok.NonNull;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiPolygon;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;
import org.jspecify.annotations.Nullable;

//TODO: move to T--
public final class LandCoverPolygon implements Bounds2d {
    private final String id;
    private final LandCoverType type;
    private final PolygonRasterizer rasterizer;

    public LandCoverPolygon(@NonNull String id, @NonNull LandCoverType type, @NonNull MultiPolygon polygons) {
        this.id = id;
        this.type = type;
        this.rasterizer = new PolygonRasterizer(polygons);
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
        boolean[][] insideMask = this.rasterizer.insideMask(gridSize, gridBaseX, gridBaseZ);
        for (int x = 0; x < gridSize; x++) {
            for (int z = 0; z < gridSize; z++) {
                if (insideMask[x + 1][z + 1] && landCover[x * gridSize + z] == null) {
                    landCover[x * gridSize + z] = this.type;
                }
            }
        }
    }

    public @NonNull String id() {
        return this.id;
    }

    public @NonNull LandCoverType type() {
        return this.type;
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
