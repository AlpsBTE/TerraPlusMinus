package de.btegermany.terraplusminus.gen.swiss.buildings3d;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import de.btegermany.terraplusminus.Terraplusminus;

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
        if (shell.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> placeSync(plugin, player, world, shell, material));
    }

    private static void placeSync(
            Terraplusminus plugin,
            Player player,
            World world,
            Set<BuildingShellVoxelizer.BlockPos> shell,
            String material
    ) {
        BlockState blockState = parseBlockState(material);
        if (blockState == null) {
            player.sendMessage("§cInvalid material: " + material);
            return;
        }

        BukkitWorld bukkitWorld = new BukkitWorld(world);

        try (
                EditSession editSession = WorldEdit.getInstance()
                        .newEditSessionBuilder()
                        .world(bukkitWorld)
                        .actor(BukkitAdapter.adapt(player))
                        .build()
        ) {
            for (BuildingShellVoxelizer.BlockPos pos : shell) {
                editSession.setBlock(BlockVector3.at(pos.x(), pos.y(), pos.z()), blockState);
            }

            // FAWE flushes the queue on close; history is saved automatically.
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
