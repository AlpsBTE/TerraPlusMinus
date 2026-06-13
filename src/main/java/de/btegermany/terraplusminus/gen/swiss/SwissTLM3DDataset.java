package de.btegermany.terraplusminus.gen.swiss;

import static net.daporkchop.lib.common.util.PorkUtil.uncheckedCast;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import lombok.NonNull;
import net.buildtheearth.terraminusminus.dataset.IElementDataset;
import net.buildtheearth.terraminusminus.dataset.TiledDataset;
import net.buildtheearth.terraminusminus.dataset.geojson.GeoJson;
import net.buildtheearth.terraminusminus.dataset.geojson.GeoJsonObject;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiPolygon;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.Polygon;
import net.buildtheearth.terraminusminus.dataset.geojson.object.Feature;
import net.buildtheearth.terraminusminus.dataset.geojson.Geometry;
import net.buildtheearth.terraminusminus.dataset.vector.geometry.VectorGeometry;
import net.buildtheearth.terraminusminus.projection.EquirectangularProjection;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.substitutes.BlockState;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;
import net.buildtheearth.terraminusminus.util.bvh.BVH;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;

/**
 * loads preprocessed GeoJSON data from the swissTLM3D dataset and extracts building footprints.
 */
public class SwissTLM3DDataset extends TiledDataset<BVH<VectorGeometry>> implements IElementDataset<BVH<VectorGeometry>> {
    private final Path tileDirectory;
    private final BlockState outlineBlock;
    private final BlockState interiorBlock;
    private final GeographicProjection geometryProjection;

    /**
     * Creates a new swissTLM3D disk-based tiled dataset that loads preprocessed Swiss building footprint GeoJSON tiles.
     *
     * @param tileDirectory the directory containing the {@code tile/x/z.json} files
     * @param outlineBlock the block state for the outermost edge of footprints
     * @param interiorBlock the block state for the interior of footprints
     * @param geometryProjection the projection used to convert WGS84 coordinates to the generator's coordinate system
     */
    public SwissTLM3DDataset(@NonNull Path tileDirectory, @NonNull BlockState outlineBlock, @NonNull BlockState interiorBlock, @NonNull GeographicProjection geometryProjection) {
        // Tiles are indexed by WGS84 equirectangular coordinates (1/32 degree)
        super(new EquirectangularProjection(), 1.0d / 32.0d);
        this.tileDirectory = tileDirectory;
        this.outlineBlock = outlineBlock;
        this.interiorBlock = interiorBlock;
        this.geometryProjection = geometryProjection;
    }

    @Override
    public @org.jspecify.annotations.NonNull CompletableFuture<BVH<VectorGeometry>> load(@NonNull ChunkPos key) {
        Path tileFile = this.tileDirectory.resolve("tile").resolve(key.x() + "/" + key.z() + ".json");
        if (!Files.exists(tileFile)) return CompletableFuture.completedFuture(BVH.of(new VectorGeometry[0]));
        List<VectorGeometry> polygons = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(tileFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                GeoJsonObject obj = GeoJson.parse(line);
                if (!(obj instanceof Feature feature)) continue;

                // convert Polygon to MultiPolygon if needed
                Geometry geometry = feature.geometry();
                if (geometry instanceof Polygon) geometry = new MultiPolygon(new Polygon[]{(Polygon) geometry});

                if (!(geometry instanceof MultiPolygon multiPolygon)) continue;
                MultiPolygon projected = multiPolygon.project(this.geometryProjection::fromGeo);

                // fill the interior with clay and the boundary with stone bricks.
                polygons.add(new OutlineFillPolygon(
                        feature.id() != null ? feature.id() : "",
                        projected,
                        this.outlineBlock,
                        this.interiorBlock
                ));
            }
        } catch (IOException | OutOfProjectionBoundsException e) {
            return CompletableFuture.completedFuture(BVH.of(new VectorGeometry[0]));
        }
        return CompletableFuture.completedFuture(BVH.of(polygons.toArray(new VectorGeometry[0])));
    }

    @Override
    public CompletableFuture<BVH<VectorGeometry>[]> getAsync(@NonNull CornerBoundingBox2d bounds) throws OutOfProjectionBoundsException {
        Bounds2d localBounds = bounds.fromGeo(this.projection).axisAlign();
        ChunkPos[] tiles = localBounds.toTiles(this.tileSize);

        // return if no tile files exist for these bounds
        boolean anyTileExists = false;
        for (ChunkPos tile : tiles) {
            Path tileFile = this.tileDirectory.resolve("tile").resolve(tile.x() + "/" + tile.z() + ".json");
            if (Files.exists(tileFile)) {
                anyTileExists = true;
                break;
            }
        }
        if (!anyTileExists) return null;

        CompletableFuture<BVH<VectorGeometry>>[] futures = uncheckedCast(Arrays.stream(tiles).map(this::getAsync).toArray(CompletableFuture[]::new));
        return CompletableFuture.allOf(futures).thenApply(unused -> uncheckedCast(Arrays.stream(futures).map(CompletableFuture::join).toArray(BVH[]::new)));
    }
}
