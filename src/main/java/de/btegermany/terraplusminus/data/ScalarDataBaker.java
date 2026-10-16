package de.btegermany.terraplusminus.data;

import net.buildtheearth.terraminusminus.dataset.IScalarDataset;
import net.buildtheearth.terraminusminus.dataset.scalar.MultiScalarDataset;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.generator.GeneratorDatasets;
import net.buildtheearth.terraminusminus.generator.data.IEarthDataBaker;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.CornerBoundingBox2d;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Bakes an optional scalar dataset into the chunk data.
 * The dataset and the chunk data use the folder path as a key, the values are a {@code float[16 * 16]}
 * (index x * 16 + z) with NaN where there is no data.
 */
public final class ScalarDataBaker implements IEarthDataBaker<float[]> {

    // 16 * 16 values without data. Shared, must not be modified
    public static final float[] NO_DATA = new float[16 * 16];

    static {
        Arrays.fill(NO_DATA, Float.NaN);
    }

    private final String name;
    private final ComponentLogger logger;

    private ScalarDataBaker(@NonNull String name, @NonNull ComponentLogger logger) {
        this.name = name;
        this.logger = logger;
    }

    /**
     * Adds a dataset and its baker if the json5 files in the dataset folder configure at least one dataset.
     * Otherwise, or if a file is invalid, the dataset is off and the chunk data has no values for it.
     *
     * @param name     folder relative to the plugin directory, e.g. {@code tree_leaf_type}
     * @param datasets datasets of the chunk data loader, the dataset is added here
     * @param bakers   bakers of the chunk data loader, the baker is added here
     */
    public static void register(
            @NonNull String name,
            @NonNull Map<String, Object> datasets,
            @NonNull List<IEarthDataBaker<?>> bakers,
            @NonNull ComponentLogger logger
    ) {
        CountedDataset dataset;
        try {
            dataset = new CountedDataset(name);
        } catch (Exception e) { // Also checked exceptions, MultiScalarDataset rethrows them unchecked
            logger.error("Invalid dataset {}, it is off: {}", name, e.getMessage());
            return;
        }
        if (dataset.size() == 0) {
            return;
        }
        logger.info("Dataset {}: on ({} datasets)", name, dataset.size());
        datasets.put(name, dataset);
        bakers.add(new ScalarDataBaker(name, logger));
    }

    @Override
    public CompletableFuture<float[]> requestData(ChunkPos pos, GeneratorDatasets datasets, Bounds2d bounds, CornerBoundingBox2d boundsGeo)
            throws OutOfProjectionBoundsException {
        return datasets.<IScalarDataset>getCustom(this.name).getAsync(boundsGeo, 16, 16)
                .thenApply(ScalarDataBaker::toFloats)
                .exceptionally(e -> {
                    this.logger.warn("Exception when reading the dataset {} at chunk {}/{}", this.name, pos.x(), pos.z(), e);
                    return null;
                });
    }

    @Override
    public void bake(ChunkPos pos, CachedChunkData.Builder builder, float[] values) {
        builder.putCustom(this.name, values != null ? values : NO_DATA);
    }

    /**
     * The datasets configured in the folder, without the Terra-- defaults.
     */
    private static final class CountedDataset extends MultiScalarDataset {

        CountedDataset(@NonNull String name) {
            super(name, false);
        }

        int size() {
            return this.bvh.size();
        }
    }

    private static float[] toFloats(double[] values) {
        if (values == null) {
            return null;
        }
        float[] floats = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            floats[i] = (float) values[i];
        }
        return floats;
    }
}
