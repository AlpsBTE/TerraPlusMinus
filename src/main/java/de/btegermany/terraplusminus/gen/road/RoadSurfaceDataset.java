package de.btegermany.terraplusminus.gen.road;

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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static net.daporkchop.lib.common.util.PorkUtil.uncheckedCast;

public final class RoadSurfaceDataset extends TiledDataset<BVH<RoadPolygon>> {
    private static final RoadPolygon[] EMPTY = new RoadPolygon[0];

    private final RoadSurfaceConfig.DatasetConfig config;
    private final RoadMaterialResolver materials;
    private final GeographicProjection geometryProjection;

    public RoadSurfaceDataset(
            @NonNull RoadSurfaceConfig.DatasetConfig config,
            @NonNull RoadMaterialResolver materials,
            @NonNull GeographicProjection geometryProjection
    ) {
        super(new EquirectangularProjection(), config.tileSizeDegrees());
        this.config = config;
        this.materials = materials;
        this.geometryProjection = geometryProjection;
    }

    @Override
    public @NonNull CompletableFuture<BVH<RoadPolygon>> load(@NonNull ChunkPos key) {
        Path tileFile = this.tileFile(key);
        if (!Files.exists(tileFile)) {
            return CompletableFuture.completedFuture(BVH.of(EMPTY));
        }

        List<RoadPolygon> polygons = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(tileFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                RoadPolygon polygon = this.parseFeature(line, key, lineNumber);
                if (polygon != null) polygons.add(polygon);
            }
        } catch (IOException | RuntimeException e) {
            return CompletableFuture.completedFuture(BVH.of(EMPTY));
        }
        return CompletableFuture.completedFuture(BVH.of(polygons.toArray(EMPTY)));
    }

    private RoadPolygon parseFeature(String line, ChunkPos tile, int lineNumber) {
        GeoJsonObject obj = GeoJson.parse(line);
        if (!(obj instanceof Feature feature)) return null;

        Map<String, String> properties = feature.properties();
        if (properties == null) return null;
        if ("tunnel".equalsIgnoreCase(properties.get("structure"))) return null;

        RoadKind kind = RoadKind.fromName(properties.get("kind"));
        if (kind == null) return null;
        RoadClass roadClass = RoadClass.fromName(properties.get("class"));
        RoadSurface surface = RoadSurface.fromName(properties.get("surface"));

        RoadMaterialRule material = this.materials.resolve(this.config.id(), kind, roadClass, surface);
        if (material == null) return null;

        Geometry geometry = feature.geometry();
        if (geometry instanceof Polygon polygon) {
            geometry = new MultiPolygon(new Polygon[]{polygon});
        }
        if (!(geometry instanceof MultiPolygon multiPolygon)) return null;

        MultiPolygon projected;
        try {
            projected = multiPolygon.project(this.geometryProjection::fromGeo);
        } catch (OutOfProjectionBoundsException e) {
            return null;
        }
        String featureId = feature.id() != null ? feature.id() : tile.x() + "_" + tile.z() + "_" + lineNumber;
        return new RoadPolygon(this.config.id() + ":" + featureId, this.config.priority(), kind, roadClass, surface, material, projected);
    }

    public CompletableFuture<BVH<RoadPolygon> @NonNull []> getAsync(@NonNull CornerBoundingBox2d bounds) throws OutOfProjectionBoundsException {
        Bounds2d localBounds = bounds.fromGeo(this.projection).axisAlign();
        ChunkPos[] tiles = localBounds.toTiles(this.tileSize);

        boolean anyTileExists = false;
        for (ChunkPos tile : tiles) {
            if (Files.exists(this.tileFile(tile))) {
                anyTileExists = true;
                break;
            }
        }
        if (!anyTileExists) return CompletableFuture.completedFuture(null);

        CompletableFuture<BVH<RoadPolygon>>[] futures = uncheckedCast(
                Arrays.stream(tiles).map(this::getAsync).toArray(CompletableFuture[]::new)
        );
        return CompletableFuture.allOf(futures).thenApply(unused ->
                uncheckedCast(Arrays.stream(futures).map(CompletableFuture::join).toArray(BVH[]::new))
        );
    }

    public @NonNull RoadSurfaceConfig.DatasetConfig config() {
        return this.config;
    }

    public void invalidateCache() {
        this.cache.invalidateAll();
    }

    private Path tileFile(ChunkPos tile) {
        return this.config.path().resolve("tile").resolve(tile.x() + "/" + tile.z() + ".json");
    }
}
