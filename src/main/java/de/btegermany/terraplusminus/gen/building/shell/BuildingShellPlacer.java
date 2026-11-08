package de.btegermany.terraplusminus.gen.building.shell;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import de.btegermany.terraplusminus.Terraplusminus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Places voxelized building shells into the world using FastAsyncWorldEdit.
 * All shells of one call go into a single edit session, so one {@code //undo} reverts all of them.
 */
public final class BuildingShellPlacer {

    private BuildingShellPlacer() {
    }

    /**
     * Schedules placement of the given shells on the main thread.
     *
     * @param plugin           the Terraplusminus plugin instance
     * @param player           the player who executes the command (for FAWE actor/undo)
     * @param world            the world to place in
     * @param shellsByMaterial voxelized shells grouped by the block material to place them with;
     *                         the materials must already be validated block materials
     * @param onSuccess        runs on the main thread after all blocks were placed and remembered for undo
     */
    public static void placeShells(
            @NonNull Terraplusminus plugin,
            @NonNull Player player,
            @NonNull World world,
            @NonNull Map<Material, List<Set<BuildingShellVoxelizer.BlockPos>>> shellsByMaterial,
            @NonNull Runnable onSuccess
    ) {
        Bukkit.getScheduler().runTask(plugin, () -> placeSync(plugin, player, world, shellsByMaterial, onSuccess));
    }

    private static void placeSync(
            Terraplusminus plugin,
            Player player,
            World world,
            Map<Material, List<Set<BuildingShellVoxelizer.BlockPos>>> shellsByMaterial,
            Runnable onSuccess
    ) {
        if (!player.isOnline()) {
            plugin.getComponentLogger().warn("Player {} logged off before building placement; aborting.", player.getName());
            return;
        }

        // Materials are validated when the dataset config is loaded, so a failure here means WorldEdit
        // doesn't know a block that Bukkit does (e.g. a version mismatch).
        Map<Material, BlockState> blockStates = new HashMap<>();
        for (Material material : shellsByMaterial.keySet()) {
            BlockType type = BukkitAdapter.asBlockType(material);
            if (type == null) {
                player.sendMessage("§cCould not place buildings: WorldEdit does not know the block " + material.getKey() + ".");
                plugin.getComponentLogger().error("WorldEdit has no block type for material {}; aborting building placement.", material.getKey());
                return;
            }
            blockStates.put(material, type.getDefaultState());
        }

        BukkitWorld bukkitWorld = new BukkitWorld(world);
        com.sk89q.worldedit.entity.Player actor = BukkitAdapter.adapt(player);
        LocalSession localSession = WorldEdit.getInstance().getSessionManager().get(actor);

        try (
                EditSession editSession = WorldEdit.getInstance()
                        .newEditSessionBuilder()
                        .world(bukkitWorld)
                        .actor(actor)
                        .build()
        ) {
            for (var entry : shellsByMaterial.entrySet()) {
                BlockState blockState = blockStates.get(entry.getKey());
                for (Set<BuildingShellVoxelizer.BlockPos> shell : entry.getValue()) {
                    for (BuildingShellVoxelizer.BlockPos pos : shell) {
                        editSession.setBlock(pos.x(), pos.y(), pos.z(), blockState);
                    }
                }
            }

            localSession.remember(editSession);
        } catch (Exception e) {
            player.sendMessage("§cError placing building blocks: " + e.getMessage());
            plugin.getComponentLogger().error("Error placing building shell blocks", e);
            return;
        }

        onSuccess.run();
    }
}
