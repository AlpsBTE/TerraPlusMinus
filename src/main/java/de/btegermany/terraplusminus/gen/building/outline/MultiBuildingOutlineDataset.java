package de.btegermany.terraplusminus.gen.building.outline;

import de.btegermany.terraplusminus.gen.building.config.BuildingOutlineConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.NonNull;
import net.buildtheearth.terraminusminus.dataset.vector.geometry.VectorGeometry;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;
import net.buildtheearth.terraminusminus.util.bvh.BVH;

public final class MultiBuildingOutlineDataset {
    public static final String KEY = "building_outlines";

    private final List<Entry> entries;

    public MultiBuildingOutlineDataset(@NonNull List<Entry> entries) {
        this.entries = entries.stream()
                .sorted(Comparator.comparingDouble((Entry entry) -> entry.config.priority()))
                .toList();
    }

    public CompletableFuture<BVH<VectorGeometry>[]> getAsync(@NonNull CornerBoundingBox2d boundsGeo) throws OutOfProjectionBoundsException {
        List<Entry> candidates = this.entries.stream()
                .filter(entry -> entry.config.enabled())
                .filter(entry -> entry.config.bounds().intersects(boundsGeo))
                .toList();
        if (candidates.isEmpty()) return CompletableFuture.completedFuture(null);

        List<CompletableFuture<BVH<VectorGeometry>[]>> futures = new ArrayList<>();
        for (Entry candidate : candidates) {
            futures.add(candidate.dataset.getAsync(boundsGeo));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(unused -> {
            List<BVH<VectorGeometry>> regions = new ArrayList<>();
            for (CompletableFuture<BVH<VectorGeometry>[]> future : futures) {
                BVH<VectorGeometry>[] datasetRegions = future.join();
                if (datasetRegions == null) continue;
                regions.addAll(List.of(datasetRegions));
            }
            return regions.isEmpty() ? null : regions.toArray(new BVH[0]);
        });
    }

    public void invalidateCache() {
        this.entries.forEach(entry -> entry.dataset.invalidateCache());
    }

    public record Entry(BuildingOutlineConfig config, BuildingOutlineDataset dataset) {
    }
}
