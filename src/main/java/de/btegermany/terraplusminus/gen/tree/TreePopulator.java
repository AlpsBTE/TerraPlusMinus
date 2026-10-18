package de.btegermany.terraplusminus.gen.tree;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.data.ScalarDataBaker;
import de.btegermany.terraplusminus.gen.CustomBiomeProvider;
import de.btegermany.terraplusminus.gen.landcover.LandCoverBaker.BakedLandCover;
import de.btegermany.terraplusminus.gen.landcover.LandCoverType;
import de.btegermany.terraplusminus.gen.landcover.MultiLandCoverDataset;
import de.btegermany.terraplusminus.utils.Properties;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorPipelines;
import net.buildtheearth.terraminusminus.generator.data.IEarthDataBaker;
import net.buildtheearth.terraminusminus.generator.data.TreeCoverBaker;
import net.buildtheearth.terraminusminus.substitutes.BlockState;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Material;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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

    // Ground slope from the heights this many blocks to each side, fewer at the chunk border
    private static final int SLOPE_SPAN = 2;

    // Chunk data of the world generator, with the tree data from addTreeData
    private final Function<ChunkPos, CompletableFuture<CachedChunkData>> chunkData;
    int yOffset;
    boolean generateTrees;
    String surface;
    CustomBiomeProvider customBiomeProvider;
    boolean landCoverEnabled;
    List<LandCoverType> treeLandCoverBlacklist;

    // List of Possible trees by type
    HashMap<String, List<TreeTemplate>> trees = new HashMap<>();

    // Which tree type grows where
    public static final String TREE_RULES_FILE = "tree_rules.json5";
    private final TreeRules rules;

    // Own tree templates. Optionally, replaces the templates in the jar
    public static final String CUSTOM_TREES_FILE = "customTrees.json";
    private static final String DEFAULT_CUSTOM_TREES_RESOURCE = "assets/terraplusminus/data/customTrees.json";

    // Optional dataset with the broadleaf share in percent (0-100). The tree cover is Terra--'s tree_cover dataset
    public static final String TREE_LEAF_TYPE_DATASET = "tree_leaf_type";
    // Optional dataset with the tree height in metres
    public static final String TREE_HEIGHT_DATASET = "tree_height";

    /**
     * @param chunkData chunk data of the world generator, which must include the tree data from {@link #addTreeData}
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
        this.landCoverEnabled = Terraplusminus.config.getBoolean(Properties.LAND_COVER_ENABLED, true);
        this.treeLandCoverBlacklist = loadTreeLandCoverBlacklist();

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
     * Adds the optional tree datasets and their bakers to the chunk data of the world generator.
     * The tree cover is already part of Terra--'s chunk data.
     *
     * @param datasets datasets of the chunk data loader
     * @param bakers   bakers of the chunk data loader
     */
    public static void addTreeData(
            @NonNull Map<String, Object> datasets,
            @NonNull List<IEarthDataBaker<?>> bakers,
            @NonNull ComponentLogger logger
    ) {
        ScalarDataBaker.register(TREE_LEAF_TYPE_DATASET, datasets, bakers, logger);
        ScalarDataBaker.register(TREE_HEIGHT_DATASET, datasets, bakers, logger);
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
        if (generateTrees) {
            try {
                CachedChunkData data = this.chunkData.apply(new ChunkPos(x, z)).get();

                // Tree chance per block (index x * 16 + z), 0-255
                byte[] treeCover = data.getCustom(EarthGeneratorPipelines.KEY_DATA_TREE_COVER, TreeCoverBaker.FALLBACK_TREE_DENSITY);
                // Broadleaf share and tree height per block (index x * 16 + z), NaN = no data
                float[] leafTypes = data.getCustom(TREE_LEAF_TYPE_DATASET, ScalarDataBaker.NO_DATA);
                float[] heights = data.getCustom(TREE_HEIGHT_DATASET, ScalarDataBaker.NO_DATA);
                // Land cover data from the world generator, used to skip trees on blacklisted cover types
                BakedLandCover landCover = this.landCoverEnabled
                        ? data.getCustom(MultiLandCoverDataset.KEY, null)
                        : null;

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

                    if (groundY < waterY || groundY + yOffset <= worldInfo.getMinHeight() || state != null
                            || this.tooSteep(data, valueX, valueZ)) {
                        continue;
                    }

                    if (this.isLandCoverBlacklisted(landCover, valueX, valueZ)) {
                        continue;
                    }

                    int treeY = groundY + 1 + yOffset;
                    if (rule == null) {
                        rule = this.rules.match(customBiomeProvider.getBiome(worldInfo, x * 16 + 8, treeY, z * 16 + 8));
                    }
                    double targetHeight = heights[valueX * 16 + valueZ];
                    if (noTreeByHeight(targetHeight)) {
                        continue;
                    }
                    String type = rule.pickType(random, leafTypes[valueX * 16 + valueZ]);
                    if (type != null) {
                        generateCustomTree(limitedRegion, worldInfo, valueX + x * 16, treeY, valueZ + z * 16, type, random, targetHeight);
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
     * @param height tree height from the tree_height dataset, NaN = no data
     * @return whether the height data says nothing reaches tree height here (value 0), so no tree grows
     */
    private static boolean noTreeByHeight(double height) {
        return height <= 0; // false for NaN
    }

    /**
     * @return whether the ground at a block is steeper than max_slope_degrees of the rules, false without a limit
     */
    private boolean tooSteep(@NonNull CachedChunkData data, int x, int z) {
        double maxSlopeDegrees = this.rules.maxSlopeDegrees();
        return !Double.isNaN(maxSlopeDegrees) && slopeDegrees(data, x, z) > maxSlopeDegrees;
    }

    /**
     * Slope of the ground at a block, from the ground heights of the surrounding blocks in the same chunk.
     *
     * @return the slope in degrees, 0 = flat, 90 = vertical
     */
    static double slopeDegrees(@NonNull CachedChunkData data, int x, int z) {
        int x0 = Math.max(x - SLOPE_SPAN, 0);
        int x1 = Math.min(x + SLOPE_SPAN, 15);
        int z0 = Math.max(z - SLOPE_SPAN, 0);
        int z1 = Math.min(z + SLOPE_SPAN, 15);
        double dx = (double) (data.groundHeight(x1, z) - data.groundHeight(x0, z)) / (x1 - x0);
        double dz = (double) (data.groundHeight(x, z1) - data.groundHeight(x, z0)) / (z1 - z0);
        return Math.toDegrees(Math.atan(Math.hypot(dx, dz)));
    }

    /**
     * Places a random template of a tree type, randomly rotated.
     * Uses the chunk's {@link Random} so the same location always gets the same tree.
     * A tree that doesn't fit between the world's min and max height is left out,
     * blocks outside the region that may be changed are skipped.
     *
     * @param targetHeight tree height at the location in blocks, NaN or not positive = no height data
     */
    public void generateCustomTree(LimitedRegion limitedRegion, WorldInfo worldInfo, int originX, int originY, int originZ,
                                   String type, Random random, double targetHeight) {

        List<TreeTemplate> trees = this.trees.get(type);

        // Random Tree
        if (trees == null || trees.isEmpty()) return;

        TreeTemplate tree = this.pickTemplate(trees, targetHeight, random);

        // Rotate Tree Randomly (drawn before the height check, so the random sequence doesn't depend on it)
        int angle = random.nextInt(4) * 90;

        if (originY + tree.minY() < worldInfo.getMinHeight() || originY + tree.maxY() >= worldInfo.getMaxHeight()) return;

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

    /**
     * Picks a random template from {@link #fittingTemplates}. Draws one random number in any case.
     *
     * @param targetHeight tree height at the location in blocks, NaN or not positive = all templates
     */
    private @NonNull TreeTemplate pickTemplate(@NonNull List<TreeTemplate> templates, double targetHeight, @NonNull Random random) {
        List<TreeTemplate> candidates = this.fittingTemplates(templates, targetHeight);
        return candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * The templates a tree is picked from: without a target height all of them, otherwise the ones within the height
     * tolerance of the rules, or the ones nearest to the target height if none is within it.
     *
     * @param templates    the templates of one tree type, not empty
     * @param targetHeight tree height at the location in blocks, NaN or not positive = all templates
     */
    private @NonNull List<TreeTemplate> fittingTemplates(@NonNull List<TreeTemplate> templates, double targetHeight) {
        if (!(targetHeight > 0)) { // also for NaN
            return templates;
        }
        double tolerance = targetHeight * this.rules.heightTolerancePercent() / 100;
        List<TreeTemplate> fitting = templates.stream()
                .filter(template -> Math.abs(template.getHeight() - targetHeight) <= tolerance)
                .toList();
        if (!fitting.isEmpty()) {
            return fitting;
        }
        double nearest = templates.stream()
                .mapToDouble(template -> Math.abs(template.getHeight() - targetHeight))
                .min()
                .orElseThrow();
        return templates.stream()
                .filter(template -> Math.abs(template.getHeight() - targetHeight) == nearest)
                .toList();
    }

    private static class TreeLoadingStatistics {
        int familyCount = 0;
        int totalVariantCount = 0;
    }

    private boolean isLandCoverBlacklisted(@Nullable BakedLandCover landCover, int x, int z) {
        if (landCover == null || this.treeLandCoverBlacklist.isEmpty()) {
            return false;
        }
        LandCoverType type = LandCoverType.values()[landCover.types()[x * 16 + z] & 0xFF];
        return this.treeLandCoverBlacklist.contains(type);
    }

    private List<LandCoverType> loadTreeLandCoverBlacklist() {
        List<?> raw = Terraplusminus.config.getList(Properties.TREE_LAND_COVER_BLACKLIST);
        if (raw == null) return List.of();
        List<LandCoverType> blacklist = new ArrayList<>();
        for (Object entry : raw) {
            LandCoverType type = LandCoverType.fromName(String.valueOf(entry));
            if (type != LandCoverType.UNKNOWN) {
                blacklist.add(type);
            }
        }
        return blacklist;
    }
}
