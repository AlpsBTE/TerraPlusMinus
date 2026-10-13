package de.btegermany.terraplusminus.gen.landcover;

import lombok.NonNull;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;
import net.buildtheearth.terraminusminus.util.bvh.BVH;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

//TODO: move to T--
public final class MultiLandCoverDataset {
    public static final String KEY = "land_cover";

    private final List<VectorLandCoverDataset> customDatasets;
    private final @Nullable OsmLandCoverDataset osmFallback;

    public MultiLandCoverDataset(
            @NonNull List<VectorLandCoverDataset> customDatasets,
            @Nullable OsmLandCoverDataset osmFallback
    ) {
        this.customDatasets = customDatasets.stream()
                .sorted(Comparator.comparingDouble(VectorLandCoverDataset::priority).reversed())
                .toList();
        this.osmFallback = osmFallback;
    }

    public CompletableFuture<List<DatasetResult>> getAsync(@NonNull CornerBoundingBox2d boundsGeo) throws OutOfProjectionBoundsException {
        List<CompletableFuture<DatasetResult>> futures = new ArrayList<>();

        for (VectorLandCoverDataset dataset : this.customDatasets) {
            futures.add(dataset.getAsync(boundsGeo).thenApply(bvhs -> new DatasetResult(dataset.priority(), bvhs)));
        }

        if (this.osmFallback != null) {
            futures.add(this.osmFallback.getAsync(boundsGeo)
                    .thenApply(bvhs -> new DatasetResult(this.osmFallback.priority(), bvhs)));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(unused -> futures.stream()
                        .map(CompletableFuture::join)
                        .sorted(Comparator.comparingDouble((DatasetResult r) -> r.priority).reversed())
                        .toList());
    }

    public void invalidateCache() {
        this.customDatasets.forEach(VectorLandCoverDataset::invalidateCache);
    }

    public record DatasetResult(double priority, BVH<LandCoverPolygon> @Nullable [] polygons) {
    }
}
