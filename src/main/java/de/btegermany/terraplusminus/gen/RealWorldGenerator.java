package de.btegermany.terraplusminus.gen;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.gen.tree.TreePopulator;
import de.btegermany.terraplusminus.utils.Properties;
import lombok.Getter;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import de.btegermany.terraplusminus.gen.swiss.SwissBuildingBaker;
import de.btegermany.terraplusminus.gen.swiss.SwissTLM3DDataset;
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
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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

    private final LoadingCache<@NotNull ChunkPos, @NotNull CompletableFuture<CachedChunkData>> cache;
    private final CustomBiomeProvider customBiomeProvider;


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

    private static final Set<Material> GRASS_LIKE_MATERIALS = Set.of(
            GRASS_BLOCK,
            DIRT_PATH,
            FARMLAND,
            MYCELIUM,
            SNOW
    );

    public RealWorldGenerator(int yOffset, Terraplusminus plugin) {

        Http.configChanged(); // This ensures the T-- default config is loaded regarding the number of concurrent http requests for specific urls.

        EarthGeneratorSettings settings = EarthGeneratorSettings.parse(EarthGeneratorSettings.BTE_DEFAULT_SETTINGS);

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

        if (plugin.getConfig().getBoolean(Properties.SWISS_BUILDINGS_ENABLED)) {
            String outlineMaterial = plugin.getConfig().getString(Properties.SWISS_BUILDINGS_OUTLINE_MATERIAL, "minecraft:stone_bricks");
            String interiorMaterial = plugin.getConfig().getString(Properties.SWISS_BUILDINGS_INTERIOR_MATERIAL, "minecraft:clay");
            BlockState outlineBlock = BlockState.parse(outlineMaterial);
            BlockState interiorBlock = BlockState.parse(interiorMaterial);

            Path swissDir = plugin.getDataFolder().toPath().resolve(plugin.getConfig().getString(Properties.SWISS_BUILDINGS_DIRECTORY, "swiss_buildings"));

            if (java.nio.file.Files.exists(swissDir)) {
                SwissTLM3DDataset swissTlm3d = new SwissTLM3DDataset(swissDir, outlineBlock, interiorBlock, this.settings.projection());
                datasetsMap.put(SwissBuildingBaker.KEY_DATASET_SWISS_BUILDINGS, swissTlm3d);

                // Append Swiss baker after all default bakers (after OSMBaker so it overwrites OSM buildings)
                bakerList.add(new SwissBuildingBaker());
                plugin.getComponentLogger().info("Swiss building dataset enabled: {}", swissDir);
            } else {
                plugin.getComponentLogger().warn("Swiss buildings are enabled but directory '{}' does not exist. Falling back to OSM only.", swissDir);
            }
        }

        GeneratorDatasets datasets = new GeneratorDatasets(datasetsMap, this.settings.projection());

        this.cache = CacheBuilder.newBuilder()
                .expireAfterAccess(5L, TimeUnit.MINUTES)
                .softValues()
                .maximumSize(256)
                .build(new ChunkDataLoader(datasets, bakerList.toArray(new IEarthDataBaker[0])));

        // This code is explicitly there for backward compatibility and is legitimate in using the deprecated config keys
        this.blockMapper = BlockMapper.fromPlugin(plugin)
                .withStaticGenericSurface(GRASS_BLOCK)
                .withConfiguredGenericSurface(Properties.SURFACE_MATERIAL)  // Overrides the static definition if present
                .withConfiguredMapping("minecraft:bricks", Properties.BUILDING_OUTLINES_MATERIAL)
                .withConfiguredMapping("minecraft:gray_concrete", Properties.ROAD_MATERIAL)
                .withConfiguredMapping("minecraft:dirt_path", Properties.PATH_MATERIAL)
                .build();
        this.defaultSurfaceBlock = this.blockMapper.genericSurfaceBlock();
    }


    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
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
                        STONE
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
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return this.customBiomeProvider;
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        CachedChunkData terraData = this.getTerraChunkData(chunkX, chunkZ);
        final int minWorldY = worldInfo.getMinHeight();
        final int maxWorldY = worldInfo.getMaxHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {

                int groundY = terraData.groundHeight(x, z) + this.yOffset;

                // We do that for each column, so it does not depend on the configuration but only on the seed
                int startMountainHeight = random.nextInt(7500, 7520);

                if (groundY < minWorldY || groundY >= maxWorldY) {
                    continue; // We are not within vertical bounds, continue
                }

                BlockData surfaceBlock = this.blockMapper.map(terraData.surfaceBlock(x, z));
                if (surfaceBlock == null) {
                    if (groundY >= startMountainHeight) {
                        surfaceBlock = this.mountainSurfaceBlock; // Mountains stay bare
                    } else {
                        // Fallback to a generic block that matches the biome, or to the default block
                        Biome biome = chunkData.getBiome(x, groundY, z);
                        surfaceBlock = this.defaultBiomeSurfaceBlocks.getOrDefault(biome, this.defaultSurfaceBlock);
                    }
                }

                // We don't want grass, snow, and all that underwater
                boolean isUnderWater = groundY + 1 >= maxWorldY || chunkData.getBlockData(x, groundY + 1, z).getMaterial().equals(WATER);
                if (isUnderWater && GRASS_LIKE_MATERIALS.contains(surfaceBlock.getMaterial())) {
                    surfaceBlock = this.underwaterBlock;
                }

                chunkData.setBlock(x, groundY, z, surfaceBlock);

            }
        }
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
    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random, int x, int z, @NotNull HeightMap heightMap) {
        int chunkX = blockToCube(x);
        int chunkZ = blockToCube(z);
        x -= cubeToMinBlock(chunkX);
        z -= cubeToMinBlock(chunkZ);
        CachedChunkData terraData = this.getTerraChunkData(chunkX, chunkZ);
        switch (heightMap) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> {
                return terraData.groundHeight(x, z) + this.yOffset;
            }
            default -> {
                return terraData.surfaceHeight(x, z) + this.yOffset;
            }
        }
    }

    @Override
    public boolean canSpawn(@NotNull World world, int x, int z) {
        Block highest = world.getBlockAt(x, world.getHighestBlockYAt(x, z), z);

        return switch (world.getEnvironment()) {
            case NETHER -> true;
            case THE_END ->
                    highest.getType() != Material.AIR && highest.getType() != WATER && highest.getType() != Material.LAVA;
            default -> highest.getType() == Material.SAND || highest.getType() == Material.GRAVEL;
        };
    }

    @Override
    @NotNull
    public List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return singletonList(new TreePopulator(this.customBiomeProvider, yOffset));
    }

    @Nullable
    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 3517417, 58, -5288234);
    }

}
