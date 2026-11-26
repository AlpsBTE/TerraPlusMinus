package de.btegermany.terraplusminus.gen.road;

import lombok.NonNull;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;
import net.buildtheearth.terraminusminus.util.bvh.BVH;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class MultiRoadSurfaceDataset {
    public static final String KEY = "road_surfaces";

    private final List<RoadSurfaceDataset> datasets;

    public MultiRoadSurfaceDataset(@NonNull List<RoadSurfaceDataset> datasets) {
        this.datasets = List.copyOf(datasets);
    }

    public CompletableFuture<@Nullable List<BVH<RoadPolygon>>> getAsync(@NonNull CornerBoundingBox2d boundsGeo) throws OutOfProjectionBoundsException {
        List<CompletableFuture<BVH<RoadPolygon>[]>> futures = new ArrayList<>();
        for (RoadSurfaceDataset dataset : this.datasets) {
            if (!dataset.config().bounds().intersects(boundsGeo)) continue;
            futures.add(dataset.getAsync(boundsGeo));
        }
        if (futures.isEmpty()) return CompletableFuture.completedFuture(null);

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(unused -> {
            List<BVH<RoadPolygon>> regions = new ArrayList<>();
            for (CompletableFuture<BVH<RoadPolygon>[]> future : futures) {
                BVH<RoadPolygon>[] datasetRegions = future.join();
                if (datasetRegions != null) regions.addAll(List.of(datasetRegions));
            }
            return regions.isEmpty() ? null : regions;
        });
    }

    public void invalidateCache() {
        this.datasets.forEach(RoadSurfaceDataset::invalidateCache);
    }
}
