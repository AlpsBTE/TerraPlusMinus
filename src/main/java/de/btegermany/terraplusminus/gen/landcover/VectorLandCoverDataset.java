package de.btegermany.terraplusminus.gen.landcover;

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
import net.buildtheearth.terraminusminus.dataset.TiledDataset;
import net.buildtheearth.terraminusminus.dataset.geojson.GeoJson;
import net.buildtheearth.terraminusminus.dataset.geojson.GeoJsonObject;
import net.buildtheearth.terraminusminus.dataset.geojson.Geometry;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiPolygon;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.Polygon;
import net.buildtheearth.terraminusminus.dataset.geojson.object.Feature;
import net.buildtheearth.terraminusminus.projection.EquirectangularProjection;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;
import net.buildtheearth.terraminusminus.util.bvh.BVH;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;

/**
 * A tiled vector land-cover dataset stored as GeoJSON feature tiles on disk.
 * <p>
 * Each feature must be a polygon whose configured property contains a canonical {@link LandCoverType}
 * name. The file layout follows the same convention as building outline datasets:
 * {@code <path>/tile/<x>/<z>.json}, one GeoJSON feature per line.
 */
public final class VectorLandCoverDataset extends TiledDataset<BVH<LandCoverPolygon>> implements LandCoverDataset {

    private final String id;
    private final Path tileDirectory;
    private final double priority;
    private final String propertyName;
    private final GeographicProjection geometryProjection;

    public VectorLandCoverDataset(
            @NonNull String id,
            @NonNull Path tileDirectory,
            double tileSizeDegrees,
            double priority,
            @NonNull String propertyName,
            @NonNull GeographicProjection geometryProjection
    ) {
        super(new EquirectangularProjection(), tileSizeDegrees);
        this.id = id;
        this.tileDirectory = tileDirectory;
        this.priority = priority;
        this.propertyName = propertyName;
        this.geometryProjection = geometryProjection;
    }

    @Override
    public @NonNull CompletableFuture<BVH<LandCoverPolygon>> load(@NonNull ChunkPos key) {
        Path tileFile = this.tileFile(key);
        if (!Files.exists(tileFile)) {
            return CompletableFuture.completedFuture(BVH.of(new LandCoverPolygon[0]));
        }

        List<LandCoverPolygon> polygons = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(tileFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                GeoJsonObject obj = GeoJson.parse(line);
                if (!(obj instanceof Feature feature)) continue;

                Geometry geometry = feature.geometry();
                if (geometry instanceof Polygon polygon) {
                    geometry = new MultiPolygon(new Polygon[]{polygon});
                }
                if (!(geometry instanceof MultiPolygon multiPolygon)) continue;

                String typeValue = feature.properties() != null ? feature.properties().get(this.propertyName) : null;
                LandCoverType type = LandCoverType.fromName(typeValue);
                if (type == LandCoverType.UNKNOWN) continue;

                MultiPolygon projected = multiPolygon.project(this.geometryProjection::fromGeo);
                String featureId = feature.id() != null ? feature.id() : "";
                polygons.add(new LandCoverPolygon(this.id + ":" + featureId, type, projected));
            }
        } catch (IOException | OutOfProjectionBoundsException e) {
            return CompletableFuture.completedFuture(BVH.of(new LandCoverPolygon[0]));
        }
        return CompletableFuture.completedFuture(BVH.of(polygons.toArray(new LandCoverPolygon[0])));
    }

    @Override
    public CompletableFuture<BVH<LandCoverPolygon> @NonNull []> getAsync(@NonNull CornerBoundingBox2d bounds) throws OutOfProjectionBoundsException {
        Bounds2d localBounds = bounds.fromGeo(this.projection).axisAlign();
        ChunkPos[] tiles = localBounds.toTiles(this.tileSize);

        boolean anyTileExists = false;
        for (ChunkPos tile : tiles) {
            if (!Files.exists(this.tileFile(tile))) continue;
            anyTileExists = true;
            break;
        }
        if (!anyTileExists) return CompletableFuture.completedFuture(null);

        CompletableFuture<BVH<LandCoverPolygon>>[] futures = uncheckedCast(
                Arrays.stream(tiles).map(this::getAsync).toArray(CompletableFuture[]::new)
        );
        return CompletableFuture.allOf(futures).thenApply(unused ->
                uncheckedCast(Arrays.stream(futures).map(CompletableFuture::join).toArray(BVH[]::new))
        );
    }

    public double priority() {
        return this.priority;
    }

    public void invalidateCache() {
        this.cache.invalidateAll();
    }

    private Path tileFile(ChunkPos tile) {
        return this.tileDirectory.resolve("tile").resolve(tile.x() + "/" + tile.z() + ".json");
    }
}
