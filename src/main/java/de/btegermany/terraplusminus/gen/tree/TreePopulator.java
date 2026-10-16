package de.btegermany.terraplusminus.gen.tree;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.gen.CustomBiomeProvider;
import de.btegermany.terraplusminus.utils.Properties;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorPipelines;
import net.buildtheearth.terraminusminus.generator.data.TreeCoverBaker;
import net.buildtheearth.terraminusminus.substitutes.BlockState;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static net.daporkchop.lib.common.util.PValidation.checkState;

public class TreePopulator extends BlockPopulator {

    // Placement attempts per chunk, each succeeding with the tree chance of the block it lands on
    private static final int TREE_ATTEMPTS_PER_CHUNK = (16 >> 1) * (16 >> 1);

    // Chunk data of the world generator
    private final Function<ChunkPos, CompletableFuture<CachedChunkData>> chunkData;
    int yOffset;
    boolean generateTrees;
    String surface;
    CustomBiomeProvider customBiomeProvider;

    // List of Possible trees by type
    HashMap<String, List<TreeTemplate>> trees = new HashMap<>();

    // Which tree type grows where
    public static final String TREE_RULES_FILE = "tree_rules.json5";
    private final TreeRules rules;

    // Own tree templates. Optionally, replaces the templates in the jar
    public static final String CUSTOM_TREES_FILE = "customTrees.json";
    private static final String DEFAULT_CUSTOM_TREES_RESOURCE = "assets/terraplusminus/data/customTrees.json";

    /**
     * @param chunkData chunk data of the world generator
     */
    public TreePopulator(
            @NonNull Function<ChunkPos, CompletableFuture<CachedChunkData>> chunkData,
            CustomBiomeProvider customBiomeProvider,
            int yOffset
    ) {
        this.chunkData = chunkData;
        this.customBiomeProvider = customBiomeProvider;
        this.yOffset = yOffset;
        this.generateTrees = Terraplusminus.config.getBoolean(Properties.GENERATE_TREES);
        this.surface = Terraplusminus.config.getString("surface_material");

        // Load trees from customTrees.json
        ComponentLogger logger = Terraplusminus.instance.getComponentLogger();
        Path customTreesFile = Terraplusminus.instance.getDataPath().resolve(CUSTOM_TREES_FILE);
        boolean loaded = false;
        if (Files.isRegularFile(customTreesFile)) {
            try (Reader reader = Files.newBufferedReader(customTreesFile, StandardCharsets.UTF_8)) {
                loadTrees(reader, customTreesFile.toString());
                loaded = true;
            } catch (IOException | RuntimeException e) {
                logger.error("Invalid tree templates file {}: {}", customTreesFile, e.getMessage());
                logger.error("Using the default tree templates instead.");
                this.trees.clear();
            }
        }
        if (!loaded) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(DEFAULT_CUSTOM_TREES_RESOURCE)) {
                checkState(is != null, "Missing internal resource: %s", DEFAULT_CUSTOM_TREES_RESOURCE);
                loadTrees(new InputStreamReader(is, StandardCharsets.UTF_8), "the plugin jar");
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load the default tree templates", e);
            }
        }

        Map<String, Integer> templateCounts = new HashMap<>();
        this.trees.forEach((type, variants) -> templateCounts.put(type, variants.size()));
        this.rules = TreeRulesLoader.loadOrDefault(
                Terraplusminus.instance.getDataPath().resolve(TREE_RULES_FILE),
                templateCounts,
                logger
        );
    }

    /**
     * Reads tree templates in the customTrees.json format into {@link #trees}.
     *
     * @param source where the templates come from, for the log
     * @throws IllegalArgumentException if the content is not a valid customTrees.json
     */
    private void loadTrees(@NonNull Reader reader, @NonNull String source) {
        JsonElement root = JsonParser.parseReader(new JsonReader(reader));
        JsonElement treesElement = root.isJsonObject() ? root.getAsJsonObject().get("trees") : null;
        if (treesElement == null || !treesElement.isJsonObject()) {
            throw new IllegalArgumentException("the file has no \"trees\" object at the top level");
        }
        JsonObject treeTypes = treesElement.getAsJsonObject();
        final TreeLoadingStatistics stats = new TreeLoadingStatistics();
        treeTypes.entrySet().forEach(treeTypeEntry -> {
            final String treeType = treeTypeEntry.getKey();
            final JsonObject treeSizeVariants = treeTypeEntry.getValue().getAsJsonObject();
            stats.familyCount++;
            Terraplusminus.instance.getComponentLogger().debug("Loading tree family {} with {} size variants", treeType, treeSizeVariants.size());

            this.trees.put(treeType, new ArrayList<>());

            treeSizeVariants.entrySet().forEach(variantEntry -> {
                final String sizeName = variantEntry.getKey();  // s, m, l, ...
                final JsonObject treeVariants = variantEntry.getValue().getAsJsonObject();
                Terraplusminus.instance.getComponentLogger().trace("Loading trees of family {} and size {} with {} variants", treeType, sizeName, treeVariants.size());

                treeVariants.entrySet().forEach(treeEntry -> {
                    final String treeName = treeEntry.getKey();
                    final JsonObject treeConfig = treeEntry.getValue().getAsJsonObject();
                    Terraplusminus.instance.getComponentLogger().trace("Loading tree variant {} of size {} and family {}", treeName, sizeName, treeType);

                    ArrayList<TreeBlock> treeBlocks = new ArrayList<>();
                    Set<String> unknownMaterials = new TreeSet<>();

                    JsonElement blocks = treeConfig.get("blocks");
                    if (blocks == null || !blocks.isJsonArray()) {
                        throw new IllegalArgumentException("tree " + treeType + "/" + sizeName + "/" + treeName + " has no \"blocks\" list");
                    }
                    blocks.getAsJsonArray().forEach(treeBlockElement -> {

                        JsonObject treeBlock = treeBlockElement.getAsJsonObject();
                        String materialName = treeBlock.get("material").getAsString();
                        Material material = Material.getMaterial(materialName);
                        if (material == null) {
                            unknownMaterials.add(materialName);
                            return;
                        }
                        treeBlocks.add(new TreeBlock(treeBlock.get("x").getAsInt(), treeBlock.get("y").getAsInt(), treeBlock.get("z").getAsInt(), material));

                    });
                    if (!unknownMaterials.isEmpty()) {
                        Terraplusminus.instance.getComponentLogger().warn("Tree {}/{}/{}: unknown materials {}, skipping these blocks", treeType, sizeName, treeName, unknownMaterials);
                    }
                    if (treeBlocks.isEmpty()) {
                        Terraplusminus.instance.getComponentLogger().warn("Tree {}/{}/{} has no blocks, skipping it", treeType, sizeName, treeName);
                        return;
                    }

                    stats.totalVariantCount++;
                    trees.get(treeType).add(TreeTemplate.of(treeType + "/" + sizeName + "/" + treeName, treeBlocks));
                });
            });
        });

        if (stats.totalVariantCount == 0) {
            throw new IllegalArgumentException("the file contains no tree templates");
        }

        Terraplusminus.instance.getComponentLogger().info("Loaded {} custom trees from {} families from {}", stats.totalVariantCount, stats.familyCount, source);
    }

    public void populate(@NonNull WorldInfo worldInfo, @NonNull Random random, int x, int z, @NonNull LimitedRegion limitedRegion) {
        World world = Bukkit.getWorld(worldInfo.getName());
        if (generateTrees && world != null) {
            try {
                CachedChunkData data = this.chunkData.apply(new ChunkPos(x, z)).get();

                // Tree chance per block (index x * 16 + z), 0-255
                byte[] treeCover = data.getCustom(EarthGeneratorPipelines.KEY_DATA_TREE_COVER, TreeCoverBaker.FALLBACK_TREE_DENSITY);

                // Rule for the biome at the chunk center
                TreeRules.TreeRule rule = null;

                for (int attempt = 0; attempt < TREE_ATTEMPTS_PER_CHUNK; attempt++) {
                    int valueX = random.nextInt(15) + 1; // Depending on the size of the tree, this should be changed
                    int valueZ = random.nextInt(15) + 1;

                    // Use the tree chance of the block the tree would be placed on
                    if (random.nextInt(256) >= (treeCover[valueX * 16 + valueZ] & 0xFF)) {
                        continue;
                    }

                    int groundY = 0;
                    int waterY = 0;
                    BlockState state = data.surfaceBlock(0, 0);

                    try {
                        groundY = data.groundHeight(valueX, valueZ);
                        waterY = data.waterHeight(valueX, valueZ);
                        state = data.surfaceBlock(valueX, valueZ);
                    } catch (IndexOutOfBoundsException e) {
                        Terraplusminus.instance.getComponentLogger().warn(
                                "Chunk boundary overflow when attempting to generate a tree at chunk {}/{}",
                                x, z
                        );
                    }

                    if (groundY < waterY || groundY + yOffset <= world.getMinHeight() || state != null) {
                        continue;
                    }

                    Location loc = new Location(world, valueX + x * 16, groundY + 1 + yOffset, valueZ + z * 16);
                    if (rule == null) {
                        rule = this.rules.match(customBiomeProvider.getBiome(worldInfo, x * 16 + 8, loc.getBlockY(), z * 16 + 8));
                    }
                    String type = rule.pickType(random);
                    if (type != null) {
                        generateCustomTree(limitedRegion, loc, type, random);
                    }
                }


            } catch (InterruptedException | ExecutionException e) {
                Terraplusminus.instance.getComponentLogger().warn(
                        "Exception when generating trees in chunk {}/{} in world {}",
                        x, z,
                        worldInfo.getName(),
                        e
                );
            }
        }
    }

    /**
     * Places a random template of a tree type, randomly rotated.
     * Uses the chunk's {@link Random} so the same location always gets the same tree.
     * A tree that doesn't fit between the world's min and max height is left out,
     * blocks outside the region that may be changed are skipped.
     */
    public void generateCustomTree(LimitedRegion limitedRegion, Location loc, String type, Random random) {

        List<TreeTemplate> trees = this.trees.get(type);

        // Random Tree
        if (trees == null || trees.isEmpty()) return;

        TreeTemplate tree = trees.get(random.nextInt(trees.size()));

        int originX = loc.getBlockX();
        int originY = loc.getBlockY();
        int originZ = loc.getBlockZ();

        // Rotate Tree Randomly (drawn before the height check, so the random sequence doesn't depend on it)
        int angle = random.nextInt(4) * 90;

        World world = loc.getWorld();
        if (originY + tree.minY() < world.getMinHeight() || originY + tree.maxY() >= world.getMaxHeight()) return;

        // Place Tree
        for (TreeBlock block : tree.blocks()) {
            int x = block.getX();
            int z = block.getZ();
            if (angle == 90) {
                int temp = x;
                x = -z;
                z = temp;
            } else if (angle == 180) {
                x = -x;
                z = -z;
            } else if (angle == 270) {
                int temp = x;
                x = z;
                z = -temp;
            }
            int blockX = originX + x;
            int blockY = originY + block.getY();
            int blockZ = originZ + z;
            if (limitedRegion.isInRegion(blockX, blockY, blockZ)) {
                limitedRegion.setType(blockX, blockY, blockZ, block.getMaterial());
            }
        }
    }

    private static class TreeLoadingStatistics {
        int familyCount = 0;
        int totalVariantCount = 0;
    }
}