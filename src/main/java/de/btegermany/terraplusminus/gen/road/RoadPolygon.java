package de.btegermany.terraplusminus.gen.road;

import de.btegermany.terraplusminus.gen.util.PolygonRasterizer;
import lombok.NonNull;
import net.buildtheearth.terraminusminus.dataset.geojson.geometry.MultiPolygon;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;

public final class RoadPolygon implements Bounds2d {
    public static final Comparator<RoadPolygon> PAINT_ORDER = Comparator
            .comparingDouble(RoadPolygon::datasetPriority).reversed()
            .thenComparing(Comparator.comparingInt((RoadPolygon p) -> p.kind().rank()).reversed())
            .thenComparing(RoadPolygon::id);

    private final String id;
    private final double datasetPriority;
    private final RoadKind kind;
    private final @Nullable RoadClass roadClass;
    private final @Nullable RoadSurface surface;
    private final RoadMaterialRule material;
    private final PolygonRasterizer rasterizer;

    public RoadPolygon(
            @NonNull String id,
            double datasetPriority,
            @NonNull RoadKind kind,
            @Nullable RoadClass roadClass,
            @Nullable RoadSurface surface,
            @NonNull RoadMaterialRule material,
            @NonNull MultiPolygon projected
    ) {
        this.id = id;
        this.datasetPriority = datasetPriority;
        this.kind = kind;
        this.roadClass = roadClass;
        this.surface = surface;
        this.material = material;
        this.rasterizer = new PolygonRasterizer(projected);
    }

    /**
     * Fills a square region of {@code gridSize}² columns starting at the given projected block coordinates.
     * Only {@code null} entries are overwritten, so polygons painted earlier keep their columns.
     */
    public void fill(@Nullable RoadPolygon @NonNull [] grid, int gridSize, int gridBaseX, int gridBaseZ) {
        boolean[][] insideMask = this.rasterizer.insideMask(gridSize, gridBaseX, gridBaseZ);
        for (int x = 0; x < gridSize; x++) {
            for (int z = 0; z < gridSize; z++) {
                if (insideMask[x + 1][z + 1] && grid[x * gridSize + z] == null) {
                    grid[x * gridSize + z] = this;
                }
            }
        }
    }

    public @NonNull String id() {
        return this.id;
    }

    public double datasetPriority() {
        return this.datasetPriority;
    }

    public @NonNull RoadKind kind() {
        return this.kind;
    }

    public @Nullable RoadClass roadClass() {
        return this.roadClass;
    }

    public @Nullable RoadSurface surface() {
        return this.surface;
    }

    public @NonNull RoadMaterialRule material() {
        return this.material;
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
