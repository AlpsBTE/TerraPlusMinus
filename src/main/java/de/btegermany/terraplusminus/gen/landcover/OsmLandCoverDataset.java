package de.btegermany.terraplusminus.gen.landcover;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import lombok.NonNull;
import net.buildtheearth.terraminusminus.dataset.IElementDataset;
import net.buildtheearth.terraminusminus.dataset.geojson.GeoJsonObject;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiPolygon;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.Polygon;
import net.buildtheearth.terraminusminus.dataset.geojson.object.Feature;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorPipelines;
import net.buildtheearth.terraminusminus.generator.GeneratorDatasets;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;
import net.buildtheearth.terraminusminus.util.bvh.BVH;

//TODO: move to T--?
public final class OsmLandCoverDataset implements LandCoverDataset {
    private static final double FALLBACK_PRIORITY = -1000.0d;

    private final GeneratorDatasets datasets;
    private final GeographicProjection projection;

    public OsmLandCoverDataset(@NonNull GeneratorDatasets datasets, @NonNull GeographicProjection projection) {
        this.datasets = datasets;
        this.projection = projection;
    }

    @Override
    public CompletableFuture<BVH<LandCoverPolygon> @NonNull []> getAsync(@NonNull CornerBoundingBox2d bounds) throws OutOfProjectionBoundsException {
        IElementDataset<GeoJsonObject[]> rawOsm = this.datasets.getCustom(EarthGeneratorPipelines.KEY_DATASET_OSM_RAW, null);
        if (rawOsm == null) {
            return CompletableFuture.completedFuture(null);
        }

        return rawOsm.getAsync(bounds).thenApply(tileArrays -> {
            if (tileArrays == null) return null;

            List<LandCoverPolygon> polygons = new ArrayList<>();
            int featureIndex = 0;
            for (GeoJsonObject[] tile : tileArrays) {
                if (tile == null) continue;
                for (GeoJsonObject obj : tile) {
                    if (!(obj instanceof Feature feature)) continue;

                    LandCoverType type = mapOsmTags(feature.properties());
                    if (type == LandCoverType.UNKNOWN) continue;

                    net.buildtheearth.terraminusminus.dataset.geojson.Geometry geometry = feature.geometry();
                    if (geometry instanceof Polygon polygon) {
                        geometry = new MultiPolygon(new Polygon[]{polygon});
                    }
                    if (!(geometry instanceof MultiPolygon multiPolygon)) continue;

                    try {
                        MultiPolygon projected = multiPolygon.project(this.projection::fromGeo);
                        polygons.add(new LandCoverPolygon("osm:" + featureIndex++, type, projected));
                    } catch (OutOfProjectionBoundsException ignored) {
                        // outside projection
                    }
                }
            }
            if (polygons.isEmpty()) return null;
            return new BVH[]{BVH.of(polygons.toArray(new LandCoverPolygon[0]))};
        });
    }

    public double priority() {
        return FALLBACK_PRIORITY;
    }

    static @NonNull LandCoverType mapOsmTags(Map<String, String> tags) {
        if (tags == null) return LandCoverType.UNKNOWN;

        String natural = tags.get("natural");
        String landUse = tags.get("landuse");
        String waterway = tags.get("waterway");
        String water = tags.get("water");

        if (water != null || "water".equals(natural) || "riverbank".equals(waterway) || "coastline".equals(natural)) {
            return LandCoverType.WATER;
        }
        if ("glacier".equals(natural)) return LandCoverType.GLACIER;
        if ("wood".equals(natural) || "forest".equals(landUse)) return LandCoverType.FOREST;
        if ("wetland".equals(natural) || "marsh".equals(natural) || "swamp".equals(natural)) return LandCoverType.WETLAND;
        if ("sand".equals(natural) || "beach".equals(natural)) return LandCoverType.SAND;
        if ("scree".equals(natural)) return LandCoverType.SCREE;
        if ("shingle".equals(natural)) return LandCoverType.SHINGLE;
        if ("bare_rock".equals(natural) || "rock".equals(natural)) return LandCoverType.BARE_ROCK;
        if ("grassland".equals(natural) || "meadow".equals(landUse) || "pasture".equals(landUse)) return LandCoverType.GRASSLAND;
        if ("farmland".equals(landUse) || "orchard".equals(landUse) || "vineyard".equals(landUse) || "plantation".equals(landUse)) {
            return LandCoverType.CROPLAND;
        }
        if ("residential".equals(landUse) || "industrial".equals(landUse) || "commercial".equals(landUse)
                || "retail".equals(landUse) || "construction".equals(landUse)) {
            return LandCoverType.URBAN;
        }
        if ("snow".equals(natural)) return LandCoverType.SNOW;

        return LandCoverType.UNKNOWN;
    }
}
