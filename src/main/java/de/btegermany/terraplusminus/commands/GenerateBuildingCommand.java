package de.btegermany.terraplusminus.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.regions.Region;
import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.gen.RealWorldGenerator;
import de.btegermany.terraplusminus.gen.building.config.BuildingDatasetConfigLoader;
import de.btegermany.terraplusminus.gen.building.config.BuildingShellConfig;
import de.btegermany.terraplusminus.gen.building.shell.Bounds;
import de.btegermany.terraplusminus.gen.building.shell.BuildingShell;
import de.btegermany.terraplusminus.gen.building.shell.BuildingShellDataset;
import de.btegermany.terraplusminus.gen.building.shell.BuildingShellPlacer;
import de.btegermany.terraplusminus.gen.building.shell.BuildingShellVoxelizer;
import de.btegermany.terraplusminus.gen.building.shell.MultiBuildingShellDataset;
import de.btegermany.terraplusminus.utils.Properties;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Generates 3D building shells from configured datasets.
 * Uses FastAsyncWorldEdit for placement so the operation is undoable.
 */
public class GenerateBuildingCommand {
    public static final String PERMISSION = "t+-.generatebuilding";
    public static final String COORDS_ARG = "coords";

    private final Terraplusminus plugin;
    private MultiBuildingShellDataset dataset;
    private final String prefix;

    public GenerateBuildingCommand(Terraplusminus plugin) {
        this.plugin = plugin;
        this.prefix = plugin.getConfig().getString(Properties.CHAT_PREFIX);
        initDataset();
    }

    public LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("generatebuilding")
                .requires(this::isPermitted)
                .then(Commands.literal("selection").executes(this::executeSelection))
                .then(Commands.literal("sel").executes(this::executeSelection))
                .then(Commands.argument(COORDS_ARG, StringArgumentType.greedyString()).executes(this::executePoint))
                .executes(this::executeSelf)
                .build();
    }

    public void reloadDataset() {
        this.dataset = null;
        initDataset();
    }

    private void initDataset() {
        List<MultiBuildingShellDataset.Entry> entries = new ArrayList<>();
        for (BuildingShellConfig config : BuildingDatasetConfigLoader.loadShells(this.plugin)) {
            if (!config.enabled()) continue;
            if (!java.nio.file.Files.exists(config.path())) {
                this.plugin.getComponentLogger().warn("Building shell dataset '{}' is enabled but directory '{}' does not exist.", config.id(), config.path());
                continue;
            }
            entries.add(new MultiBuildingShellDataset.Entry(config, new BuildingShellDataset(config.id(), config.path())));
            this.plugin.getComponentLogger().info("Building shell dataset enabled: {} ({})", config.id(), config.path());
        }
        this.dataset = entries.isEmpty() ? null : new MultiBuildingShellDataset(entries);
    }

    private int executePoint(@NotNull CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            sender.sendMessage(prefix + "§cThis command can only be executed by a player.");
            return Command.SINGLE_SUCCESS;
        }

        String coordsArg = ctx.getArgument(COORDS_ARG, String.class);
        String normalizedArg = coordsArg.trim();
        if ("selection".equalsIgnoreCase(normalizedArg) || "sel".equalsIgnoreCase(normalizedArg)) {
            return executeSelection(ctx);
        }

        String[] parts = coordsArg.trim().split("\\s+");

        Double overrideRadius = null;
        String coordsStr = coordsArg;

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

        return runPointGeneration(player, sender, latLng.getLat(), latLng.getLng(), overrideRadius);
    }

    private int executeSelf(@NotNull CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            sender.sendMessage(prefix + "§cThis command can only be executed by a player.");
            return Command.SINGLE_SUCCESS;
        }

        GenerationContext context = resolveContext(player, sender);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }

        double[] geo;
        try {
            geo = context.projection().toGeo(player.getLocation().getX(), player.getLocation().getZ());
        } catch (OutOfProjectionBoundsException e) {
            sender.sendMessage(prefix + "§cYour current location is outside projection bounds.");
            return Command.SINGLE_SUCCESS;
        }

        return runPointGeneration(player, sender, geo[1], geo[0], null);
    }

    private int executeSelection(@NotNull CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            sender.sendMessage(prefix + "§cThis command can only be executed by a player.");
            return Command.SINGLE_SUCCESS;
        }

        GenerationContext context = resolveContext(player, sender);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }

        Region region = getSelection(player, sender, context.world());
        if (region == null) {
            return Command.SINGLE_SUCCESS;
        }

        HorizontalSelection selection = HorizontalSelection.from(region);
        GeoBounds geoBounds;
        try {
            geoBounds = selection.toGeoBounds(context.projection());
        } catch (OutOfProjectionBoundsException e) {
            sender.sendMessage(prefix + "§cYour WorldEdit selection is outside projection bounds.");
            return Command.SINGLE_SUCCESS;
        }

        sender.sendMessage(prefix + "§7Searching selection for buildings...");

        CompletableFuture.supplyAsync(() -> dataset.findBuildingsIntersecting(
                        geoBounds.minLon(),
                        geoBounds.minLat(),
                        geoBounds.maxLon(),
                        geoBounds.maxLat()
                ))
                .thenAccept(candidates -> {
                    if (candidates.isEmpty()) {
                        Bukkit.getScheduler().runTask(this.plugin, () ->
                                sender.sendMessage(prefix + "§cNo buildings found inside the current WorldEdit selection."));
                        return;
                    }

                    final int candidateCount = candidates.size();
                    Bukkit.getScheduler().runTask(this.plugin, () ->
                            sender.sendMessage(prefix + "§7Found " + candidateCount + " buildings, voxelizing..."));

                    CompletableFuture.supplyAsync(() -> generateSelectionBatch(context, selection, candidates))
                            .thenAccept(result -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                                if (result == null || result.shellsByMaterial().isEmpty()) {
                                    sender.sendMessage(prefix + "§cNo buildings could be voxelized inside the current WorldEdit selection.");
                                    return;
                                }

                                sender.sendMessage(prefix + "§7Generating " + result.buildings() + " buildings (" + result.blocks() + " shell blocks)...");
                                for (Map.Entry<String, List<Set<BuildingShellVoxelizer.BlockPos>>> entry : result.shellsByMaterial().entrySet()) {
                                    BuildingShellPlacer.placeShells(this.plugin, player, context.world(), entry.getValue(), entry.getKey());
                                }
                                sender.sendMessage(prefix + "§aGenerated " + result.buildings() + " buildings. Use §7//undo§a to revert.");
                            }))
                            .exceptionally(ex -> {
                                Bukkit.getScheduler().runTask(this.plugin, () -> sender.sendMessage(prefix + "§cError generating buildings: " + ex.getMessage()));
                                this.plugin.getComponentLogger().error("Error generating building shells from selection", ex);
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(this.plugin, () -> sender.sendMessage(prefix + "§cError searching for buildings: " + ex.getMessage()));
                    this.plugin.getComponentLogger().error("Error searching for building shells from selection", ex);
                    return null;
                });

        return Command.SINGLE_SUCCESS;
    }

    private int runPointGeneration(Player player, CommandSender sender, double lat, double lon, Double overrideRadius) {
        GenerationContext context = resolveContext(player, sender);
        if (context == null) {
            return Command.SINGLE_SUCCESS;
        }

        double radius = overrideRadius != null ? overrideRadius : dataset.defaultSearchRadius(lon, lat);

        sender.sendMessage(prefix + "§7Searching for building...");

        CompletableFuture.supplyAsync(() -> dataset.findNearestBuilding(lon, lat, overrideRadius))
                .thenApply(shell -> {
                    if (shell == null) return null;
                    Set<BuildingShellVoxelizer.BlockPos> voxels = BuildingShellVoxelizer.voxelize(shell, context.projection(), context.yOffset());
                    return new PointGenerationResult(voxels, dataset.materialFor(shell));
                })
                .thenAccept(result -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                    if (result == null) {
                        sender.sendMessage(prefix + "§cNo building found within " + radius + " meters of those coordinates.");
                        return;
                    }
                    if (result.voxels().isEmpty()) {
                        sender.sendMessage(prefix + "§cBuilding found but could not be voxelized.");
                        return;
                    }
                    sender.sendMessage(prefix + "§7Generating " + result.voxels().size() + " shell blocks...");
                    BuildingShellPlacer.place(this.plugin, player, context.world(), result.voxels(), result.material());
                    sender.sendMessage(prefix + "§aBuilding generated. Use §7//undo§a to revert.");
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(this.plugin, () -> sender.sendMessage(prefix + "§cError generating building: " + ex.getMessage()));
                    this.plugin.getComponentLogger().error("Error generating building shell", ex);
                    return null;
                });

        return Command.SINGLE_SUCCESS;
    }

    private SelectionGenerationResult generateSelectionBatch(
            GenerationContext context,
            HorizontalSelection selection,
            List<BuildingShell> candidates
    ) {
        if (candidates.isEmpty()) {
            return SelectionGenerationResult.empty();
        }

        Map<String, List<Set<BuildingShellVoxelizer.BlockPos>>> matchedShells = new HashMap<>();
        long totalBlocks = 0L;
        int totalBuildings = 0;

        for (BuildingShell shell : candidates) {
            ProjectedBounds projectedBounds = projectBounds(shell.bounds(), context.projection());
            if (projectedBounds == null || !selection.intersects(projectedBounds)) {
                continue;
            }

            Set<BuildingShellVoxelizer.BlockPos> voxels = BuildingShellVoxelizer.voxelize(shell, context.projection(), context.yOffset());
            if (voxels.isEmpty() || !intersectsSelection(selection, voxels)) {
                continue;
            }

            matchedShells.computeIfAbsent(dataset.materialFor(shell), unused -> new ArrayList<>()).add(voxels);
            totalBlocks += voxels.size();
            totalBuildings++;
        }

        return matchedShells.isEmpty()
                ? SelectionGenerationResult.empty()
                : new SelectionGenerationResult(totalBuildings, totalBlocks, matchedShells);
    }

    private GenerationContext resolveContext(Player player, CommandSender sender) {
        if (dataset == null) {
            sender.sendMessage(prefix + "§cNo building shell dataset is loaded or enabled.");
            return null;
        }

        RealWorldGenerator terraGenerator = findTerraGenerator(player);
        if (terraGenerator == null) {
            sender.sendMessage(prefix + "§cThis is not a Terraplusminus world.");
            return null;
        }

        EarthGeneratorSettings settings = terraGenerator.getSettings();
        GeographicProjection projection = settings.projection();
        int yOffset = terraGenerator.getYOffset();
        World world = player.getWorld();
        return new GenerationContext(terraGenerator, projection, yOffset, world);
    }

    private Region getSelection(Player player, CommandSender sender, World world) {
        try {
            com.sk89q.worldedit.entity.Player actor = BukkitAdapter.adapt(player);
            LocalSession localSession = WorldEdit.getInstance().getSessionManager().get(actor);
            return localSession.getSelection(BukkitAdapter.adapt(world));
        } catch (IncompleteRegionException e) {
            sender.sendMessage(prefix + "§cMake a WorldEdit selection first, then run §7/generatebuilding selection§c.");
            return null;
        } catch (Exception e) {
            sender.sendMessage(prefix + "§cCould not read your WorldEdit selection: " + e.getMessage());
            return null;
        }
    }

    private static boolean intersectsSelection(
            HorizontalSelection selection,
            Set<BuildingShellVoxelizer.BlockPos> voxels
    ) {
        for (BuildingShellVoxelizer.BlockPos pos : voxels) {
            if (selection.contains(pos.x(), pos.z())) return true;
        }
        return false;
    }

    private static ProjectedBounds projectBounds(Bounds bounds, GeographicProjection projection) {
        try {
            double[][] corners = new double[][]{
                    {bounds.minLon(), bounds.minLat()},
                    {bounds.minLon(), bounds.maxLat()},
                    {bounds.maxLon(), bounds.minLat()},
                    {bounds.maxLon(), bounds.maxLat()}
            };

            double minX = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (double[] corner : corners) {
                double[] projected = projection.fromGeo(corner[0], corner[1]);
                minX = Math.min(minX, projected[0]);
                minZ = Math.min(minZ, projected[1]);
                maxX = Math.max(maxX, projected[0]);
                maxZ = Math.max(maxZ, projected[1]);
            }

            return new ProjectedBounds(
                    (int) Math.floor(minX),
                    (int) Math.floor(minZ),
                    (int) Math.ceil(maxX),
                    (int) Math.ceil(maxZ)
            );
        } catch (OutOfProjectionBoundsException e) {
            return null;
        }
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

    private record GenerationContext(
            RealWorldGenerator terraGenerator,
            GeographicProjection projection,
            int yOffset,
            World world
    ) {
    }

    private record PointGenerationResult(Set<BuildingShellVoxelizer.BlockPos> voxels, String material) {
    }

    private record SelectionGenerationResult(
            int buildings,
            long blocks,
            Map<String, List<Set<BuildingShellVoxelizer.BlockPos>>> shellsByMaterial
    ) {
        static SelectionGenerationResult empty() {
            return new SelectionGenerationResult(0, 0L, Map.of());
        }
    }

    private record GeoBounds(double minLon, double minLat, double maxLon, double maxLat) {
    }

    private record ProjectedBounds(int minX, int minZ, int maxX, int maxZ) {
    }

    private record SelectionPoint(double x, double z) {
    }

    private record HorizontalSelection(
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            List<SelectionPoint> polygon
    ) {
        static HorizontalSelection from(Region region) {
            int minX = region.getMinimumPoint().x();
            int maxX = region.getMaximumPoint().x();
            int minZ = region.getMinimumPoint().z();
            int maxZ = region.getMaximumPoint().z();

            List<BlockVector2> polygonPoints = region.polygonize(-1);
            List<SelectionPoint> points = new ArrayList<>(polygonPoints.size());
            for (BlockVector2 point : polygonPoints) {
                points.add(new SelectionPoint(point.x(), point.z()));
            }

            if (points.size() < 3) {
                points = List.of(
                        new SelectionPoint(minX, minZ),
                        new SelectionPoint(minX, maxZ + 1.0d),
                        new SelectionPoint(maxX + 1.0d, maxZ + 1.0d),
                        new SelectionPoint(maxX + 1.0d, minZ)
                );
            }

            return new HorizontalSelection(minX, maxX, minZ, maxZ, points);
        }

        GeoBounds toGeoBounds(GeographicProjection projection) throws OutOfProjectionBoundsException {
            double minLon = Double.POSITIVE_INFINITY;
            double minLat = Double.POSITIVE_INFINITY;
            double maxLon = Double.NEGATIVE_INFINITY;
            double maxLat = Double.NEGATIVE_INFINITY;

            List<SelectionPoint> samples = new ArrayList<>(this.polygon.size() + 4);
            samples.addAll(this.polygon);
            samples.add(new SelectionPoint(this.minX, this.minZ));
            samples.add(new SelectionPoint(this.minX, this.maxZ + 1.0d));
            samples.add(new SelectionPoint(this.maxX + 1.0d, this.minZ));
            samples.add(new SelectionPoint(this.maxX + 1.0d, this.maxZ + 1.0d));

            for (SelectionPoint point : samples) {
                double[] geo = projection.toGeo(point.x(), point.z());
                minLon = Math.min(minLon, geo[0]);
                minLat = Math.min(minLat, geo[1]);
                maxLon = Math.max(maxLon, geo[0]);
                maxLat = Math.max(maxLat, geo[1]);
            }

            return new GeoBounds(minLon, minLat, maxLon, maxLat);
        }

        boolean intersects(ProjectedBounds bounds) {
            return this.minX <= bounds.maxX() && this.maxX >= bounds.minX()
                    && this.minZ <= bounds.maxZ() && this.maxZ >= bounds.minZ();
        }

        boolean contains(int x, int z) {
            if (x < this.minX || x > this.maxX || z < this.minZ || z > this.maxZ) return false;
            if (this.polygon.size() < 3) return true;

            double px = x + 0.5d;
            double pz = z + 0.5d;
            boolean inside = false;

            for (int i = 0, j = this.polygon.size() - 1; i < this.polygon.size(); j = i++) {
                SelectionPoint current = this.polygon.get(i);
                SelectionPoint previous = this.polygon.get(j);

                if (pointOnSegment(px, pz, previous, current)) return true;

                boolean intersects = ((current.z() > pz) != (previous.z() > pz))
                        && (px < (previous.x() - current.x()) * (pz - current.z()) / (previous.z() - current.z()) + current.x());
                if (intersects) inside = !inside;
            }

            return inside;
        }

        private static boolean pointOnSegment(double px, double pz, SelectionPoint a, SelectionPoint b) {
            double cross = (px - a.x()) * (b.z() - a.z()) - (pz - a.z()) * (b.x() - a.x());
            if (Math.abs(cross) > 1.0e-9d) return false;

            double dot = (px - a.x()) * (b.x() - a.x()) + (pz - a.z()) * (b.z() - a.z());
            if (dot < 0.0d) return false;

            double squaredLength = (b.x() - a.x()) * (b.x() - a.x()) + (b.z() - a.z()) * (b.z() - a.z());
            return dot <= squaredLength;
        }
    }
}
