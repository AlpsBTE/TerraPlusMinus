package de.btegermany.terraplusminus.gen.road;

import de.btegermany.terraplusminus.gen.building.config.DatasetExtent;
import org.jspecify.annotations.NonNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public record RoadSurfaceConfig(
        boolean enabled,
        @NonNull List<DatasetConfig> datasets,
        @NonNull List<RoadMaterialRule> materials
) {
    public static final String COVERAGE_FILE = "coverage.geojson";

    public static @NonNull RoadSurfaceConfig disabled() {
        return new RoadSurfaceConfig(false, List.of(), List.of());
    }

    public boolean isActive() {
        return this.enabled && !this.datasets.isEmpty() && !this.materials.isEmpty();
    }

    public record DatasetConfig(
            @NonNull String id,
            double priority,
            @NonNull DatasetExtent bounds,
            @NonNull Path path,
            double tileSizeDegrees,
            boolean replaceOsmRoads,
            @NonNull Set<String> keepOsmHighways
    ) {
        public @NonNull Path coverageFile() {
            return this.path.resolve(COVERAGE_FILE);
        }
    }
}
