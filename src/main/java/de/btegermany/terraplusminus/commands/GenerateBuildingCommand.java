package de.btegermany.terraplusminus.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.gen.RealWorldGenerator;
import de.btegermany.terraplusminus.gen.swiss.buildings3d.BuildingShellVoxelizer;
import de.btegermany.terraplusminus.gen.swiss.buildings3d.Swiss3DBuildingPlacer;
import de.btegermany.terraplusminus.gen.swiss.buildings3d.SwissBuildings3DDataset;
import de.btegermany.terraplusminus.utils.Properties;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.buildtheearth.terraminusminus.generator.EarthGeneratorSettings;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.util.geo.CoordinateParseUtils;
import net.buildtheearth.terraminusminus.util.geo.LatLng;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;

/**
 * Command handler for /generatebuilding.
 * <p>
 * Generates a 3D building shell from the SwissBuildings3D dataset at the given coordinates.
 * Uses FastAsyncWorldEdit for placement so the operation is undoable.
 */
public class GenerateBuildingCommand {
    public static final String PERMISSION = "t+-.generatebuilding";
    public static final String COORDS_ARG = "coords";

    private final Terraplusminus plugin;
    private SwissBuildings3DDataset dataset;
    private String prefix;

    public GenerateBuildingCommand(Terraplusminus plugin) {
        this.plugin = plugin;
        this.prefix = plugin.getConfig().getString(Properties.CHAT_PREFIX);
        initDataset();
    }

    public LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("generatebuilding")
                .requires(this::isPermitted)
                .then(Commands.argument(COORDS_ARG, StringArgumentType.greedyString()).executes(this::execute))
                .executes(this::executeSelf)
                .build();
    }

    private void initDataset() {
        if (!this.plugin.getConfig().getBoolean(Properties.SWISS_BUILDINGS_3D_ENABLED, false)) return;

        Path dir = this.plugin
                .getDataFolder()
                .toPath()
                .resolve(this.plugin.getConfig().getString(Properties.SWISS_BUILDINGS_3D_DIRECTORY, "swiss_buildings_3d"));
        if (java.nio.file.Files.exists(dir)) dataset = new SwissBuildings3DDataset(dir);
    }

    private int execute(@NotNull CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            sender.sendMessage(prefix + "§cThis command can only be executed by a player.");
            return Command.SINGLE_SUCCESS;
        }

        String coordsArg = ctx.getArgument(COORDS_ARG, String.class);
        String[] parts = coordsArg.trim().split("\\s+");

        Double overrideRadius = null;
        String coordsStr = coordsArg;

        // Try to parse the last token as a radius
        if (parts.length >= 2) {
            try {
                overrideRadius = Double.parseDouble(parts[parts.length - 1]);
                coordsStr = String.join(" ", Arrays.copyOf(parts, parts.length - 1));
            } catch (NumberFormatException ignored) {
                // Last token is not a number, treat entire string as coordinates
            }
        }

        LatLng latLng = CoordinateParseUtils.parseVerbatimCoordinates(coordsStr);
        if (latLng == null) {
            sender.sendMessage(prefix + "§cInvalid coordinates. Use: /generatebuilding <lat> <lon> [radius]");
            return Command.SINGLE_SUCCESS;
        }

        return runGeneration(player, sender, latLng.getLat(), latLng.getLng(), overrideRadius);
    }

    private int executeSelf(@NotNull CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            sender.sendMessage(prefix + "§cThis command can only be executed by a player.");
            return Command.SINGLE_SUCCESS;
        }

        RealWorldGenerator terraGenerator = findTerraGenerator(player);
        if (terraGenerator == null) {
            sender.sendMessage(prefix + "§cThis is not a Terraplusminus world.");
            return Command.SINGLE_SUCCESS;
        }

        double x = player.getLocation().getX();
        double z = player.getLocation().getZ();
        GeographicProjection projection = terraGenerator.getSettings().projection();
        double[] geo;
        try {
            geo = projection.toGeo(x, z);
        } catch (OutOfProjectionBoundsException e) {
            sender.sendMessage(prefix + "§cYour current location is outside projection bounds.");
            return Command.SINGLE_SUCCESS;
        }

        return runGeneration(player, sender, geo[1], geo[0], null);
    }

    private int runGeneration(Player player, CommandSender sender, double lat, double lon, Double overrideRadius) {
        if (dataset == null) {
            sender.sendMessage(prefix + "§cSwissBuildings3D dataset is not loaded or disabled.");
            return Command.SINGLE_SUCCESS;
        }

        RealWorldGenerator terraGenerator = findTerraGenerator(player);
        if (terraGenerator == null) {
            sender.sendMessage(prefix + "§cThis is not a Terraplusminus world.");
            return Command.SINGLE_SUCCESS;
        }

        EarthGeneratorSettings settings = terraGenerator.getSettings();
        GeographicProjection projection = settings.projection();
        int yOffset = terraGenerator.getYOffset();
        World world = player.getWorld();
        String material = this.plugin.getConfig().getString(Properties.SWISS_BUILDINGS_3D_MATERIAL, "minecraft:stone");
        double radius = overrideRadius != null
                ? overrideRadius
                : this.plugin.getConfig().getDouble(Properties.SWISS_BUILDINGS_3D_RADIUS, 10.0d);

        sender.sendMessage(prefix + "§7Searching for building...");

        // Run the heavy work off the main thread
        CompletableFuture.supplyAsync(() -> dataset.findNearestBuilding(lon, lat, radius))
                .thenApplyAsync(shell -> {
                    if (shell == null) return null;
                    Set<BuildingShellVoxelizer.BlockPos> voxels = BuildingShellVoxelizer.voxelize(shell, projection, yOffset);
                    return voxels;
                })
                .thenAccept(voxels -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                    if (voxels == null) {
                        sender.sendMessage(prefix + "§cNo building found within " + radius + " meters of those coordinates.");
                        return;
                    }
                    if (voxels.isEmpty()) {
                        sender.sendMessage(prefix + "§cBuilding found but could not be voxelized.");
                        return;
                    }
                    sender.sendMessage(prefix + "§7Generating " + voxels.size() + " shell blocks...");
                    Swiss3DBuildingPlacer.place(this.plugin, player, world, voxels, material);
                    sender.sendMessage(prefix + "§aBuilding generated. Use §7//undo§a to revert.");
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(this.plugin, () -> sender.sendMessage(prefix + "§cError generating building: " + ex.getMessage()));
                    this.plugin.getComponentLogger().error("Error generating Swiss3D building", ex);
                    return null;
                });

        return Command.SINGLE_SUCCESS;
    }

    private boolean isPermitted(@NotNull CommandSourceStack source) {
        return source.getSender().hasPermission(PERMISSION);
    }

    private static RealWorldGenerator findTerraGenerator(Player player) {
        World world = player.getWorld();
        ChunkGenerator generator = world.getGenerator();
        if (generator instanceof RealWorldGenerator rg) return rg;
        for (World w : Bukkit.getWorlds()) {
            if (w.getGenerator() instanceof RealWorldGenerator rg) return rg;
        }
        return null;
    }
}
