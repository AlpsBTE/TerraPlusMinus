package de.btegermany.terraplusminus.gen;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.gen.building.config.BuildingDatasetConfigLoader;
import de.btegermany.terraplusminus.gen.building.config.BuildingOutlineConfig;
import de.btegermany.terraplusminus.gen.building.outline.BuildingOutlineBaker;
import de.btegermany.terraplusminus.gen.building.outline.BuildingOutlineDataset;
import de.btegermany.terraplusminus.gen.building.outline.MultiBuildingOutlineDataset;
import de.btegermany.terraplusminus.gen.landcover.LandCoverBaker;
import de.btegermany.terraplusminus.gen.landcover.LandCoverDatasetConfigLoader;
import de.btegermany.terraplusminus.gen.landcover.LandCoverDatasetConfigLoader.LandCoverConfig;
import de.btegermany.terraplusminus.gen.landcover.LandCoverDatasetConfigLoader.LandCoverDatasetConfig;
import de.btegermany.terraplusminus.gen.landcover.LandCoverType;
import de.btegermany.terraplusminus.gen.landcover.MultiLandCoverDataset;
import de.btegermany.terraplusminus.gen.landcover.OsmLandCoverDataset;
import de.btegermany.terraplusminus.gen.landcover.VectorLandCoverDataset;
import de.btegermany.terraplusminus.gen.snow.Aspect;
import de.btegermany.terraplusminus.gen.snow.SnowModel;
import de.btegermany.terraplusminus.gen.surface.*;
import de.btegermany.terraplusminus.gen.tree.TreePopulator;
import de.btegermany.terraplusminus.utils.Properties;
import lombok.Getter;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.generator.ChunkDataLoader;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorPipelines;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorSettings;
import net.buildtheearth.terraminusminus.generator.GeneratorDatasets;
import net.buildtheearth.terraminusminus.generator.data.IEarthDataBaker;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.transform.OffsetProjectionTransform;
import net.buildtheearth.terraminusminus.substitutes.BlockState;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.buildtheearth.terraminusminus.util.http.Http;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Snow;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Collections.singletonList;
import static net.buildtheearth.terraminusminus.substitutes.ChunkPos.blockToCube;
import static net.buildtheearth.terraminusminus.substitutes.ChunkPos.cubeToMinBlock;
import static org.bukkit.Material.*;
import static org.bukkit.block.Biome.*;

/**
 * A world generator using Terra-- as the generation engine.
 * It is opinionated and optimized for BTE creative building
 * (very bland terrain with no features at all).
 */
public class RealWorldGenerator extends ChunkGenerator {

    @Getter
    private final EarthGeneratorSettings settings;
    @Getter
    private final int yOffset;
    @Getter
    private final GeneratorDatasets datasets;

    private final LoadingCache<@NonNull ChunkPos, @NonNull CompletableFuture<CachedChunkData>> cache;
    private final CustomBiomeProvider customBiomeProvider;


    private final BlockData baseBlock;
    private final BlockData defaultSurfaceBlock;
    private final BlockData mountainSurfaceBlock = STONE.createBlockData();
    private final BlockData underwaterBlock = DIRT.createBlockData();
    private final Map<Biome, BlockData> defaultBiomeSurfaceBlocks = Map.of(
            DESERT, SAND.createBlockData(),
            SNOWY_SLOPES, SNOW_BLOCK.createBlockData(),
            SNOWY_PLAINS, SNOW_BLOCK.createBlockData(),
            FROZEN_PEAKS, SNOW_BLOCK.createBlockData()
    );
    private final BlockMapper blockMapper;

    private final SurfacePatternsConfig surfacePatterns;
    private final SurfacePatternResolver surfacePatternResolver;

    private final boolean snowEnabled;
    private final SnowModel snowModel;
    private final BlockData snowBlock = SNOW_BLOCK.createBlockData();
    private final BlockData[] snowLayerBlocks = new BlockData[9]; // indices 1..8

    /**
     * Memoized Bukkit conversions of the surface patterns' stratum blocks: cliff faces
     * resolve the same few configured blocks over and over, and the conversion is not free.
     */
    private final Map<@NonNull BlockState, @NonNull BlockData> stratumBlockCache = new ConcurrentHashMap<>();

    private static final Set<Material> GRASS_LIKE_MATERIALS = Set.of(
            GRASS_BLOCK,
            DIRT_PATH,
            FARMLAND,
            MYCELIUM,
            SNOW
    );

    public RealWorldGenerator(int yOffset, @NonNull Terraplusminus plugin) {

        Http.configChanged(); // This ensures the T-- default config is loaded regarding the number of concurrent http requests for specific urls.

        EarthGeneratorSettings settings = EarthGeneratorSettings.parse(EarthGeneratorSettings.BTE_DEFAULT_SETTINGS);
        if (!plugin.getConfig().getBoolean(Properties.GENERATE_TREES)) settings.withUseDefaultTreeCover(false);

        GeographicProjection projection = new OffsetProjectionTransform(
                settings.projection(),
                plugin.getConfig().getInt(Properties.X_OFFSET),
                plugin.getConfig().getInt(Properties.Z_OFFSET)
        );
        if (yOffset == 0) {
            this.yOffset = plugin.getConfig().getInt(Properties.Y_OFFSET);
        } else {
            this.yOffset = yOffset;
        }

        this.settings = settings.withProjection(projection);

        this.customBiomeProvider = new CustomBiomeProvider(projection);

        // Build datasets and bakers
        Map<String, Object> datasetsMap = new HashMap<>(EarthGeneratorPipelines.datasets(this.settings));
        List<IEarthDataBaker<?>> bakerList = new ArrayList<>(
                Arrays.asList(EarthGeneratorPipelines.dataBakers(this.settings))
        );

        List<MultiBuildingOutlineDataset.Entry> buildingOutlineEntries = new ArrayList<>();
        for (BuildingOutlineConfig config : BuildingDatasetConfigLoader.loadOutlines(plugin)) {
            if (!config.enabled()) continue;
            if (!java.nio.file.Files.exists(config.path())) {
                plugin.getComponentLogger().warn("Building outline dataset '{}' is enabled but directory '{}' does not exist.", config.id(), config.path());
                continue;
            }
            BlockState outlineBlock = BlockState.parse(config.outlineMaterial());
            BlockState interiorBlock = BlockState.parse(config.interiorMaterial());
            buildingOutlineEntries.add(new MultiBuildingOutlineDataset.Entry(
                    config,
                    new BuildingOutlineDataset(
                            config.id(),
                            config.path(),
                            config.tileSizeDegrees(),
                            config.priority(),
                            outlineBlock,
                            interiorBlock,
                            this.settings.projection()
                    )
            ));
            plugin.getComponentLogger().info("Building outline dataset enabled: {} ({})", config.id(), config.path());
        }
        if (!buildingOutlineEntries.isEmpty()) {
            datasetsMap.put(MultiBuildingOutlineDataset.KEY, new MultiBuildingOutlineDataset(buildingOutlineEntries));
            bakerList.add(new BuildingOutlineBaker());
        }

        // surface patterns (loaded early so the landcover baker can request the margin needed for edge falloff)
        this.surfacePatterns = SurfacePatternLoader.load(plugin.getComponentLogger());
        this.surfacePatternResolver = new SurfacePatternResolver(this.surfacePatterns.getPatterns());
        int maxLandCoverEdgeFalloff = this.surfacePatterns.isEnabled()
                ? this.surfacePatterns.getPatterns().stream()
                        .mapToInt(SurfacePattern::getLandCoverEdgeFalloff)
                        .max().orElse(0)
                : 0;

        boolean landCoverEnabled = plugin.getConfig().getBoolean(Properties.LAND_COVER_ENABLED, true);
        if (landCoverEnabled) {
            LandCoverConfig landCoverConfig = LandCoverDatasetConfigLoader.load(plugin);
            List<VectorLandCoverDataset> landCoverDatasets = new ArrayList<>();
            for (LandCoverDatasetConfig datasetConfig : landCoverConfig.datasets()) {
                landCoverDatasets.add(new VectorLandCoverDataset(
                        datasetConfig.id(),
                        datasetConfig.tileDirectory(),
                        datasetConfig.tileSizeDegrees(),
                        datasetConfig.priority(),
                        datasetConfig.propertyName(),
                        this.settings.projection()
                ));
            }
            OsmLandCoverDataset osmFallback = new OsmLandCoverDataset(new GeneratorDatasets(datasetsMap, this.settings.projection()), this.settings.projection());
            MultiLandCoverDataset landCoverDataset = new MultiLandCoverDataset(landCoverDatasets, osmFallback);
            bakerList.add(new LandCoverBaker(landCoverDataset, maxLandCoverEdgeFalloff));
        }

        this.datasets = new GeneratorDatasets(datasetsMap, this.settings.projection());

        this.cache = CacheBuilder.newBuilder()
                .expireAfterAccess(5L, TimeUnit.MINUTES)
                .softValues()
                .build(new ChunkDataLoader(this.datasets, bakerList.toArray(new IEarthDataBaker[0])));

        String baseMaterialName = plugin.getConfig().getString(Properties.BASE_MATERIAL, STONE.name());
        Material baseMaterial = baseMaterialName == null ? STONE : Material.matchMaterial(baseMaterialName.trim());
        if (baseMaterial == null || !baseMaterial.isBlock()) {
            plugin.getComponentLogger().warn("Invalid base material '{}', falling back to STONE", baseMaterialName);
            baseMaterial = STONE;
        }
        this.baseBlock = baseMaterial.createBlockData();

        // This code is explicitly there for backward compatibility and is legitimate in using the deprecated config keys
        this.blockMapper = BlockMapper.fromPlugin(plugin)
                .withStaticGenericSurface(GRASS_BLOCK)
                .withConfiguredGenericSurface(Properties.SURFACE_MATERIAL)  // Overrides the static definition if present
                .withConfiguredMapping("minecraft:bricks", Properties.BUILDING_OUTLINES_MATERIAL)
                .withConfiguredMapping("minecraft:gray_concrete", Properties.ROAD_MATERIAL)
                .withConfiguredMapping("minecraft:dirt_path", Properties.PATH_MATERIAL)
                .build();
        this.defaultSurfaceBlock = this.blockMapper.genericSurfaceBlock();

        // snow generation
        this.snowEnabled = plugin.getConfig().getBoolean(Properties.SNOW_GENERATION_ENABLED, true);
        if (this.snowEnabled) {
            this.snowModel = new SnowModel(
                    plugin.getConfig().getInt(Properties.SNOW_MIN_HEIGHT, 2800),
                    plugin.getConfig().getDouble(Properties.SNOW_ASPECT_BONUS, 250.0d),
                    plugin.getConfig().getDouble(Properties.SNOW_SLOPE_MIN, 15.0d),
                    plugin.getConfig().getDouble(Properties.SNOW_SLOPE_RANGE, 55.0d),
                    plugin.getConfig().getDouble(Properties.SNOW_HEIGHT_MID, 3300.0d),
                    plugin.getConfig().getDouble(Properties.SNOW_SLOPE_STEEPNESS, 0.0035d)
            );
            for (int i = 1; i <= 8; i++) {
                Snow snow = (Snow) SNOW.createBlockData();
                snow.setLayers(i);
                this.snowLayerBlocks[i] = snow;
            }
        } else this.snowModel = null;

        if (this.snowEnabled) {
            plugin.getComponentLogger().info(
                    "Snow generation enabled (min_height={}, aspect_bonus={}, height_mid={}, slope_min={}, slope_range={}, slope_steepness={})",
                    this.snowModel.minHeight(),
                    this.snowModel.aspectBonus(),
                    this.snowModel.heightMid(),
                    this.snowModel.slopeMin(),
                    this.snowModel.slopeRange(),
                    this.snowModel.slopeSteepness()
            );
        } else {
            plugin.getComponentLogger().info("Snow generation disabled");
        }
    }


    @Override
    public void generateNoise(@NonNull WorldInfo worldInfo, @NonNull Random random, int chunkX, int chunkZ, @NonNull ChunkData chunkData) {
        CachedChunkData terraData = this.getTerraChunkData(chunkX, chunkZ);

        int minWorldY = worldInfo.getMinHeight();
        int maxWorldY = worldInfo.getMaxHeight();

        // Optimization: if the entire chunk is above the surface, there is nothing to do
        int minSurfaceCubeY = blockToCube(minWorldY - this.yOffset);
        if (terraData.aboveSurface(minSurfaceCubeY)) {
            return;
        }

        // And now, we build the actual terrain shape
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int groundHeight = min(terraData.groundHeight(x, z) + this.yOffset, maxWorldY - 1);
                int waterHeight = min(terraData.waterHeight(x, z) + this.yOffset, maxWorldY - 1);
                chunkData.setRegion(
                        x, minWorldY, z,
                        x + 1, groundHeight + 1, z + 1,
                        this.baseBlock
                );
                chunkData.setRegion(
                        x, groundHeight + 1, z,
                        x + 1, waterHeight + 1, z + 1,
                        WATER
                );
            }
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NonNull WorldInfo worldInfo) {
        return this.customBiomeProvider;
    }

    @Override
    public void generateSurface(@NonNull WorldInfo worldInfo, @NonNull Random random, int chunkX, int chunkZ, @NonNull ChunkData chunkData) {
        CachedChunkData terraData = this.getTerraChunkData(chunkX, chunkZ);
        final int minWorldY = worldInfo.getMinHeight();
        final int maxWorldY = worldInfo.getMaxHeight();

        final boolean patternsEnabled = this.surfacePatterns.isEnabled() && !this.surfacePatternResolver.isEmpty();
        final boolean snowEnabled = this.snowEnabled;
        final ChunkNeighborhood neighborhood = (patternsEnabled || snowEnabled)
                ? new ChunkNeighborhood(chunkX, chunkZ, terraData, this::getTerraChunkData)
                : null;
        final int slopeRadius = this.surfacePatterns.getSlopeRadius();
        final int baseBlockX = cubeToMinBlock(chunkX);
        final int baseBlockZ = cubeToMinBlock(chunkZ);
        final LandCoverData landCoverData = this.getChunkLandCover(terraData);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {

                int groundY = terraData.groundHeight(x, z) + this.yOffset;

                // We do that for each column, so it does not depend on the configuration but only on the seed
                int startMountainHeight = random.nextInt(7500, 7520);

                // The highest block of this column within the world's vertical bounds
                int topY = min(groundY, maxWorldY - 1);
                if (topY < minWorldY) {
                    continue; // We are not within vertical bounds, continue
                }

                int blockX = baseBlockX + x;
                int blockZ = baseBlockZ + z;

                // The surface block on top of the column
                // OSM and building outline data always have priority
                BlockData surfaceBlock = this.blockMapper.map(terraData.surfaceBlock(x, z));
                LandCoverType landCover = landCoverData != null ? landCoverData.types()[x * 16 + z] : null;
                int edgeDistance = landCoverData != null && landCoverData.edgeDistances() != null
                        ? landCoverData.edgeDistances()[x * 16 + z] & 0xFF
                        : Integer.MAX_VALUE;
                if (surfaceBlock == null && patternsEnabled) {
                    int irlHeight = terraData.groundHeight(x, z);
                    double slopeAngle = Slope.angleDegrees(neighborhood, blockX, blockZ, slopeRadius);
                    SurfacePattern pattern = this.surfacePatternResolver.resolve(irlHeight, slopeAngle, landCover, edgeDistance, blockX, topY, blockZ).orElse(null);
                    if (pattern != null) {
                        surfaceBlock = this.applyStrata(chunkData, pattern, x, topY, groundY, z, blockX, blockZ, minWorldY);
                    }
                }
                if (surfaceBlock == null) {
                    if (groundY >= startMountainHeight) {
                        surfaceBlock = this.mountainSurfaceBlock; // Mountains stay bare
                    } else {
                        // Fallback to a generic block that matches the biome, or to the default block
                        Biome biome = chunkData.getBiome(x, topY, z);
                        surfaceBlock = this.defaultBiomeSurfaceBlocks.getOrDefault(biome, this.defaultSurfaceBlock);
                    }
                }

                // We don't want grass, snow, and all that underwater
                boolean isUnderWater = topY + 1 >= maxWorldY || chunkData.getBlockData(x, topY + 1, z).getMaterial().equals(WATER);
                if (isUnderWater && GRASS_LIKE_MATERIALS.contains(surfaceBlock.getMaterial())) {
                    surfaceBlock = this.underwaterBlock;
                }

                chunkData.setBlock(x, topY, z, surfaceBlock);

                // The blocks that the column exposes on its flank get their own pattern resolution
                if (patternsEnabled) {
                    this.applyCliffFaces(chunkData, neighborhood, slopeRadius, x, topY, groundY, z, blockX, blockZ, minWorldY, maxWorldY, landCover, edgeDistance);
                }

            }
        }

        // snow applied after all surface patterns are resolved
        if (snowEnabled && neighborhood != null) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    // prevent snow on OSM/building outlines
                    if (terraData.surfaceBlock(x, z) != null) continue;

                    int groundHeight = terraData.groundHeight(x, z);
                    int groundY = groundHeight + this.yOffset;
                    int topY = min(groundY, maxWorldY - 1);
                    if (topY < minWorldY || topY + 1 >= maxWorldY) continue;
                    if (chunkData.getBlockData(x, topY + 1, z).getMaterial() != AIR) {
                        continue; // water, plant, or some other block in the way
                    }

                    int blockX = baseBlockX + x;
                    int blockZ = baseBlockZ + z;

                    double slopeAngle = Slope.angleDegrees(neighborhood, blockX, blockZ, slopeRadius);
                    Aspect.Result aspect = Aspect.compute(this.settings.projection(), neighborhood, blockX, blockZ, slopeRadius);
                    if (!aspect.valid()) continue;

                    if (!this.snowModel.hasSnow(groundHeight, slopeAngle, aspect.azimuth(), aspect.southernHemisphere())) {
                        continue;
                    }

                    int layers = this.snowLayers(neighborhood, blockX, blockZ, groundHeight, slopeAngle, aspect);

                    chunkData.setBlock(x, topY, z, this.snowBlock);
                    chunkData.setBlock(x, topY + 1, z, this.snowLayerBlocks[layers]);
                }
            }
        }
    }

    private int snowLayers(
            @NonNull ChunkNeighborhood neighborhood, int blockX, int blockZ,
            int groundHeight, double slopeAngle, Aspect.Result aspect
    ) {
        // local 3x3 mean of the integer heights
        int heightSum = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                heightSum += neighborhood.height(blockX + dx, blockZ + dz);
            }
        }
        double smoothHeight = heightSum / 9.0d;
        double residual = smoothHeight - groundHeight;

        int layers = SnowModel.frostLayers(residual);
        if (layers > 1) {
            // cap by the eligible columns in the 3x3 neighborhood
            int eligible = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int neighborHeight = neighborhood.height(blockX + dx, blockZ + dz);
                    if (this.snowModel.hasSnow(neighborHeight, slopeAngle, aspect.azimuth(), aspect.southernHemisphere())) {
                        eligible++;
                    }
                }
            }
            layers = SnowModel.edgeRamp(layers, eligible);
        }
        return layers;
    }

    private @Nullable BlockData applyStrata(
            @NonNull ChunkData chunkData, @NonNull SurfacePattern pattern,
            int x, int topY, int groundY, int z, int blockX, int blockZ, int minWorldY
    ) {
        BlockData surfaceBlock = this.pickStratumBlock(pattern.stratumAt(groundY - topY), blockX, topY, blockZ);
        for (int y = topY - 1; y >= minWorldY; y--) {
            SurfacePattern.Stratum stratum = pattern.finiteStratumAt(groundY - y);
            if (stratum == null) break;
            chunkData.setBlock(x, y, z, Objects.requireNonNull(this.pickStratumBlock(stratum, blockX, y, blockZ)));
        }
        return surfaceBlock;
    }

    /**
     * Applies surface patterns to the blocks that a terrain column exposes on its flank.
     * So all blocks between the lowest neighboring surface and the column's own surface.
     * <p>
     * Each exposed block is resolved on its own, by its real-world height and the steepest terrain angle among the
     * directions that expose it, and shows the stratum revealed at its depth below the column's surface.
     */
    private void applyCliffFaces(
            @NonNull ChunkData chunkData, @NonNull ChunkNeighborhood neighborhood,
            int slopeRadius, int x, int topY, int groundY, int z,
            int blockX, int blockZ, int minWorldY, int maxWorldY, LandCoverType landCover, int edgeDistance
    ) {
        int neighborMinusX = neighborhood.height(blockX - 1, blockZ) + this.yOffset;
        int neighborPlusX = neighborhood.height(blockX + 1, blockZ) + this.yOffset;
        int neighborMinusZ = neighborhood.height(blockX, blockZ - 1) + this.yOffset;
        int neighborPlusZ = neighborhood.height(blockX, blockZ + 1) + this.yOffset;
        int minNeighborY = min(min(neighborMinusX, neighborPlusX), min(neighborMinusZ, neighborPlusZ));
        if (minNeighborY >= topY - 1) {
            return; // no block below the surface is exposed on the flank
        }

        // The face angles do not depend on the exposed height, so measure them once per direction
        double angleMinusX = Slope.angleDegreesToward(neighborhood, blockX, blockZ, -1, 0, slopeRadius);
        double anglePlusX = Slope.angleDegreesToward(neighborhood, blockX, blockZ, 1, 0, slopeRadius);
        double angleMinusZ = Slope.angleDegreesToward(neighborhood, blockX, blockZ, 0, -1, slopeRadius);
        double anglePlusZ = Slope.angleDegreesToward(neighborhood, blockX, blockZ, 0, 1, slopeRadius);

        for (int y = max(minNeighborY + 1, minWorldY); y < topY; y++) {
            // The steepest angle among the directions that expose this block
            double faceAngle = 0.0;
            if (neighborMinusX < y) {
                faceAngle = Math.max(faceAngle, angleMinusX);
            }
            if (neighborPlusX < y) {
                faceAngle = Math.max(faceAngle, anglePlusX);
            }
            if (neighborMinusZ < y) {
                faceAngle = Math.max(faceAngle, angleMinusZ);
            }
            if (neighborPlusZ < y) {
                faceAngle = Math.max(faceAngle, anglePlusZ);
            }

            // Resolve the block by its own real-world height and the face's steepness
            SurfacePattern pattern = this.surfacePatternResolver.resolve(y - this.yOffset, faceAngle, landCover, edgeDistance, blockX, y, blockZ).orElse(null);
            if (pattern == null) continue;
            // The stratum that is revealed at this depth below the column's surface
            SurfacePattern.Stratum stratum = pattern.stratumAt(groundY - y);
            if (stratum == null) {
                continue; // The pattern's finite strata do not reach this deep
            }
            BlockData block = this.pickStratumBlock(stratum, blockX, y, blockZ);

            // We don't want grass, snow, and all that underwater
            boolean isUnderWater = y + 1 >= maxWorldY || chunkData.getBlockData(x, y + 1, z).getMaterial().equals(WATER);
            if (isUnderWater && GRASS_LIKE_MATERIALS.contains(block.getMaterial())) {
                block = this.underwaterBlock;
            }

            chunkData.setBlock(x, y, z, block);
        }
    }

    /**
     * Picks the block to place for one revealed stratum: a mix value, derived from the block's real-world position,
     * selects the entry of the stratum's weighted block mix.
     */
    private @Nullable BlockData pickStratumBlock(SurfacePattern.@Nullable Stratum stratum, int blockX, int y, int blockZ) {
        if (stratum == null) return null;
        SurfacePatternLayer layer = stratum.layer();
        int irlY = y - this.yOffset;
        double mixValue;
        if (layer.getMode() == SurfacePatternLayer.Mode.NOISE) {
            mixValue = layer.isNoise3d()
                    ? NoiseSampler.sample3d(stratum.seed(), layer.getNoiseScale(), blockX, irlY, blockZ)
                    : NoiseSampler.sample2d(stratum.seed(), layer.getNoiseScale(), blockX, blockZ);
        } else {
            mixValue = SpeckleHash.value(stratum.seed(), blockX, irlY, blockZ);
        }
        return this.mapStratumBlock(layer.pick(mixValue).getBlock());
    }

    private @NonNull BlockData mapStratumBlock(@NonNull BlockState state) {
        return this.stratumBlockCache.computeIfAbsent(state, this.blockMapper::map);
    }

    private record LandCoverData(LandCoverType @NonNull [] types, byte @Nullable [] edgeDistances) {
    }

    private @Nullable LandCoverData getChunkLandCover(@NonNull CachedChunkData terraData) {
        Object raw = terraData.getCustom(MultiLandCoverDataset.KEY, null);
        if (raw instanceof LandCoverBaker.BakedLandCover data) {
            LandCoverType[] types = new LandCoverType[16 * 16];
            for (int i = 0; i < 16 * 16; i++) {
                types[i] = LandCoverType.values()[data.types()[i] & 0xFF];
            }
            return new LandCoverData(types, data.edgeDistances());
        }
        if (raw instanceof byte[] data) {
            LandCoverType[] types = new LandCoverType[16 * 16];
            for (int i = 0; i < 16 * 16; i++) {
                types[i] = LandCoverType.values()[data[i] & 0xFF];
            }
            return new LandCoverData(types, null);
        }
        return null;
    }

    private CachedChunkData getTerraChunkData(int chunkX, int chunkZ) {
        try {
            return this.cache.getUnchecked(new ChunkPos(chunkX, chunkZ)).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Unrecoverable exception when generating chunk data asynchronously in Terra--", e);
        }
    }

    /**
     * Gets Chunk Data async with the supplied chunk coordinates.
     *
     * @param chunkX The Chunk X coordinate
     * @param chunkZ The Chunk Z coordinate
     * @return A CompletableFuture containing the CachedChunkData
     */
    public CompletableFuture<CachedChunkData> getBaseHeightAsync(int chunkX, int chunkZ) {
        return this.cache.getUnchecked(new ChunkPos(chunkX, chunkZ));
    }

    @Override
    public int getBaseHeight(@NonNull WorldInfo worldInfo, @NonNull Random random, int x, int z, @NonNull HeightMap heightMap) {
        int chunkX = blockToCube(x);
        int chunkZ = blockToCube(z);
        x -= cubeToMinBlock(chunkX);
        z -= cubeToMinBlock(chunkZ);
        CachedChunkData terraData = this.getTerraChunkData(chunkX, chunkZ);
        return switch (heightMap) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> terraData.groundHeight(x, z) + this.yOffset;
            default -> terraData.surfaceHeight(x, z) + this.yOffset;
        };
    }

    @Override
    public boolean canSpawn(@NonNull World world, int x, int z) {
        Block highest = world.getBlockAt(x, world.getHighestBlockYAt(x, z), z);

        return switch (world.getEnvironment()) {
            case NETHER -> true;
            case THE_END ->
                    highest.getType() != Material.AIR && highest.getType() != WATER && highest.getType() != Material.LAVA;
            default -> highest.getType() == Material.SAND || highest.getType() == Material.GRAVEL;
        };
    }

    @Override
    @NonNull
    public List<BlockPopulator> getDefaultPopulators(@NonNull World world) {
        return singletonList(new TreePopulator(this.customBiomeProvider, yOffset));
    }

    @Nullable
    @Override
    public Location getFixedSpawnLocation(@NonNull World world, @NonNull Random random) {
        return new Location(world, 3517417, 58, -5288234);
    }

}
