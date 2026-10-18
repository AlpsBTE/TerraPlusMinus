package de.btegermany.terraplusminus.gen.building.shell;

import de.btegermany.terraplusminus.gen.building.config.BuildingShellConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.NonNull;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;

public final class MultiBuildingShellDataset {
    private final List<Entry> entries;

    public MultiBuildingShellDataset(@NonNull List<Entry> entries) {
        this.entries = entries.stream()
                .filter(entry -> entry.config.enabled())
                .sorted(Comparator.comparingDouble((Entry entry) -> entry.config.priority()).reversed())
                .toList();
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    public BuildingShell findNearestBuilding(double lon, double lat, Double radiusOverride) {
        for (Entry entry : this.entries) {
            if (!entry.config.bounds().toBounds2d().intersects(Bounds2d.of(lon, lon, lat, lat))) continue;
            double radius = radiusOverride != null ? radiusOverride : entry.config.searchRadius();
            BuildingShell shell = entry.dataset.findNearestBuilding(lon, lat, radius);
            if (shell != null) return shell;
        }
        return null;
    }

    public List<BuildingShell> findBuildingsIntersecting(double minLon, double minLat, double maxLon, double maxLat) {
        List<BuildingShell> accepted = new ArrayList<>();
        Bounds2d queryBounds = Bounds2d.of(minLon, maxLon, minLat, maxLat);
        for (Entry entry : this.entries) {
            if (!entry.config.bounds().toBounds2d().intersects(queryBounds)) continue;
            for (BuildingShell candidate : entry.dataset.findBuildingsIntersecting(minLon, minLat, maxLon, maxLat)) {
                boolean overlapsHigherPriority = false;
                for (BuildingShell existing : accepted) {
                    // Only skip if the candidate overlaps a building from a different (higher-prio) dataset.
                    // Buildings within the same dataset may legitimately overlap (like multi-part city blocks).
                    if (existing.datasetId().equals(entry.config().id())) continue;
                    if (candidate.footprintOverlaps(existing)) {
                        overlapsHigherPriority = true;
                        break;
                    }
                }
                if (!overlapsHigherPriority) accepted.add(candidate);
            }
        }
        return accepted;
    }

    public String materialFor(BuildingShell shell) {
        for (Entry entry : this.entries) {
            if (entry.config.id().equals(shell.datasetId())) {
                return entry.config.material();
            }
        }
        return "minecraft:stone";
    }

    public double defaultSearchRadius(double lon, double lat) {
        for (Entry entry : this.entries) {
            if (entry.config.bounds().toBounds2d().intersects(Bounds2d.of(lon, lon, lat, lat))) {
                return entry.config.searchRadius();
            }
        }
        return 10.0d;
    }

    public record Entry(BuildingShellConfig config, BuildingShellDataset dataset) {
    }
}
