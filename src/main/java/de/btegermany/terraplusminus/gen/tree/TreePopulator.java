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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jspecify.annotations.NonNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;


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
    HashMap<String, ArrayList<ArrayList<TreeBlock>>> trees = new HashMap<>();


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

        // Load Trees from customTrees.json
        JsonObject treeTypes = getJSONObject();
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

                    stats.totalVariantCount++;
                    ArrayList<TreeBlock> treeBlocks = new ArrayList<>();

                    treeConfig.get("blocks").getAsJsonArray().forEach(treeBlockElement -> {

                        JsonObject treeBlock = treeBlockElement.getAsJsonObject();
                        treeBlocks.add(new TreeBlock(treeBlock.get("x").getAsInt(), treeBlock.get("y").getAsInt(), treeBlock.get("z").getAsInt(), Material.getMaterial(treeBlock.get("material").getAsString())));

                    });

                    trees.get(treeTypeEntry.getKey()).add(treeBlocks);

                });

            });

        });
        Terraplusminus.instance.getComponentLogger().info("Loaded {} custom trees from {} families", stats.totalVariantCount, stats.familyCount);

    }

    public void populate(@NonNull WorldInfo worldInfo, @NonNull Random random, int x, int z, @NonNull LimitedRegion limitedRegion) {
        World world = Bukkit.getWorld(worldInfo.getName());
        if (generateTrees && world != null) {
            try {
                CachedChunkData data = this.chunkData.apply(new ChunkPos(x, z)).get();

                // Tree chance per block (index x * 16 + z), 0-255
                byte[] treeCover = data.getCustom(EarthGeneratorPipelines.KEY_DATA_TREE_COVER, TreeCoverBaker.FALLBACK_TREE_DENSITY);

                for (int attempt = 0; attempt < TREE_ATTEMPTS_PER_CHUNK; attempt++) {
                    int valueX = random.nextInt(15) + 1; // Depending on the size of the tree this should be changed
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

                    if (groundY < waterY) {
                        continue;
                    }

                    Location loc = new Location(world, valueX + x * 16, groundY + 1 + yOffset, valueZ + z * 16);
                    if (groundY + yOffset < world.getMaxHeight() - 35 && groundY + yOffset > world.getMinHeight() && state == null) {
                        switch ((int) customBiomeProvider.getBiome()) {
                            case 4, 6, 17: // desert and savanna
                                generateCustomTree(limitedRegion, loc, "savanna");
                                break;
                            case 14, 15: // flower forest
                                generateCustomTree(limitedRegion, loc, "oak", "birch");
                                break;
                            case 27: // taiga
                                generateCustomTree(limitedRegion, loc, "spruce");
                                break;
                            case 28, 29, 30: // snowy regions
                                // TODO: trees with snow
                                break;
                            default:
                                generateCustomTree(limitedRegion, loc, "oak", "birch");
                                break;
                        }
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

    public void generateCustomTree(LimitedRegion limitedRegion, Location loc, String... types) {

        ArrayList<ArrayList<TreeBlock>> trees = new ArrayList<>();
        for (String type : types) {
            trees.addAll(this.trees.get(type));
        }

        // Random Tree
        if (trees.isEmpty()) return;

        int randTree = (new Random()).nextInt(trees.size());
        if (randTree > trees.size() - 1) randTree = trees.size() - 1;
        ArrayList<TreeBlock> tree = trees.get(randTree);

        int originX = loc.getBlockX();
        int originY = loc.getBlockY();
        int originZ = loc.getBlockZ();


        // Rotate Tree Randomly
        Random rand = new Random();
        int angle = rand.nextInt(4) * 90;

        // Place Tree
        for (TreeBlock block : tree) {
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
            limitedRegion.setType(originX + x, originY + block.getY(), originZ + z, block.getMaterial());
        }
    }

    public JsonObject getJSONObject() {
        InputStream is = getClass().getClassLoader().getResourceAsStream("assets/terraplusminus/data/customTrees.json");

        JsonReader reader;
        assert is != null;
        reader = new JsonReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        JsonElement jsonElement = JsonParser.parseReader(reader);
        JsonObject jsonObject = jsonElement.getAsJsonObject();
        return jsonObject.get("trees").getAsJsonObject();
    }

    private static class TreeLoadingStatistics {
        int familyCount = 0;
        int totalVariantCount = 0;
    }

}