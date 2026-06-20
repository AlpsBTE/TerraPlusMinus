package de.btegermany.terraplusminus.gen.swiss.buildings3d;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import de.btegermany.terraplusminus.Terraplusminus;

import java.util.Collection;
import java.util.Set;

import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Places a voxelized building shell into the world using FastAsyncWorldEdit.
 * Changes are tracked under the player's undo history.
 */
public final class Swiss3DBuildingPlacer {

    /**
     * Places the shell blocks using FAWE so the operation is undoable.
     *
     * @param plugin   the Terraplusminus plugin instance
     * @param player   the player who executes the command (for FAWE actor/undo)
     * @param world    the world to place in
     * @param shell    the set of block positions to place
     * @param material the material string, e.g. "minecraft:stone"
     */
    public static void place(
            @NonNull Terraplusminus plugin,
            @NonNull Player player,
            @NonNull World world,
            @NonNull Set<BuildingShellVoxelizer.BlockPos> shell,
            @NonNull String material
    ) {
        placeShells(plugin, player, world, java.util.List.of(shell), material);
    }

    public static void placeShells(
            @NonNull Terraplusminus plugin,
            @NonNull Player player,
            @NonNull World world,
            @NonNull Collection<? extends Set<BuildingShellVoxelizer.BlockPos>> shells,
            @NonNull String material
    ) {
        if (shells.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> placeSync(plugin, player, world, shells, material));
    }

    private static void placeSync(
            Terraplusminus plugin,
            Player player,
            World world,
            Collection<? extends Set<BuildingShellVoxelizer.BlockPos>> shells,
            String material
    ) {
        if (!player.isOnline()) {
            plugin.getComponentLogger().warn("Player {} logged off before building placement; aborting.", player.getName());
            return;
        }

        BlockState blockState = parseBlockState(material);
        if (blockState == null) {
            player.sendMessage("§cInvalid material: " + material);
            return;
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
            for (Set<BuildingShellVoxelizer.BlockPos> shell : shells) {
                if (shell.isEmpty()) continue;
                for (BuildingShellVoxelizer.BlockPos pos : shell) {
                    editSession.setBlock(BlockVector3.at(pos.x(), pos.y(), pos.z()), blockState);
                }
            }

            localSession.remember(editSession);
        } catch (Exception e) {
            player.sendMessage("§cError placing building blocks: " + e.getMessage());
            plugin.getComponentLogger().error("Error placing Swiss3D building blocks", e);
        }
    }

    private static BlockState parseBlockState(String material) {
        try {
            // Normalize to the key format WorldEdit expects
            String key = material.toLowerCase();
            if (!key.contains(":")) key = "minecraft:" + key;
            BlockType type = BlockTypes.get(key);
            return (type != null ? type : BlockTypes.STONE).getDefaultState();
        } catch (Exception e) {
            return BlockTypes.STONE.getDefaultState();
        }
    }
}
