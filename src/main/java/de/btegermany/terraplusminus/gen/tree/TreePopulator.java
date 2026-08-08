package de.btegermany.terraplusminus.gen.tree;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.gen.CustomBiomeProvider;
import de.btegermany.terraplusminus.utils.Properties;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.generator.ChunkDataLoader;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorPipelines;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorSettings;
import net.buildtheearth.terraminusminus.generator.data.TreeCoverBaker;
import net.buildtheearth.terraminusminus.substitutes.BlockState;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import net.daporkchop.lib.common.reference.ReferenceStrength;
import net.daporkchop.lib.common.reference.cache.Cached;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;


public class TreePopulator extends BlockPopulator {

    public static final Cached<byte[]> RNG_CACHE = Cached.threadLocal(() -> new byte[16 * 16], ReferenceStrength.SOFT);
    public final LoadingCache<ChunkPos, CompletableFuture<CachedChunkData>> cache;
    private final EarthGeneratorSettings bteGeneratorSettings = EarthGeneratorSettings.parse(EarthGeneratorSettings.BTE_DEFAULT_SETTINGS);
    ChunkDataLoader loader = new ChunkDataLoader(bteGeneratorSettings);
    int xOffset;
    int yOffset;
    int zOffset;
    boolean generateTrees; // Should Trees be added to the Terrain
    String surface;
    CustomBiomeProvider customBiomeProvider;

    // List of Possible trees by type
    HashMap<String, ArrayList<ArrayList<TreeBlock>>> trees = new HashMap<>();


    public TreePopulator(CustomBiomeProvider customBiomeProvider, int yOffset) {
        this.customBiomeProvider = customBiomeProvider;
        this.xOffset = Terraplusminus.config.getInt("terrain_offset.x");
        this.yOffset = yOffset;
        this.zOffset = Terraplusminus.config.getInt("terrain_offset.z");
        this.generateTrees = Terraplusminus.config.getBoolean(Properties.GENERATE_TREES);
        this.surface = Terraplusminus.config.getString("surface_material");
        this.cache = CacheBuilder.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(5))
                .softValues()
                .build(new ChunkDataLoader(this.bteGeneratorSettings));


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
                CachedChunkData data = this.loader.load(new ChunkPos(x - (xOffset / 16), z - (zOffset / 16))).get();

                byte[] treeCover = data.getCustom(EarthGeneratorPipelines.KEY_DATA_TREE_COVER, TreeCoverBaker.FALLBACK_TREE_DENSITY);
                byte[] rng = RNG_CACHE.get();

                for (int i = 0, dx = 0; dx < 16 >> 1; dx++) {
                    for (int dz = 0; dz < 16 >> 1; dz++, i++) {
                        if ((rng[i] & 0xFF) < (treeCover[(0)] & 0xFF)) {
                            random.nextBytes(rng);

                            int valueX = random.nextInt(15) + 1; // Depending on the size of the tree this should be changed
                            int valueZ = random.nextInt(15) + 1;
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
                                return;
                            }

                            Location loc = new Location(world, valueX + x * 16, groundY + 1 + yOffset, valueZ + z * 16); // is offset missing?
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