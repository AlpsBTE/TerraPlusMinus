package de.btegermany.terraplusminus.events;

import de.btegermany.terraplusminus.utils.ConfigurationHelper;
import de.btegermany.terraplusminus.utils.LinkedWorld;
import de.btegermany.terraplusminus.utils.LinkedWorldLayout;
import de.btegermany.terraplusminus.utils.Permission;
import de.btegermany.terraplusminus.utils.Properties;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.NonNull;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


public class PlayerMoveEvent implements Listener {

    /**
     * A player who ends up this many blocks below their world's downward trigger was dragged past it
     * by a lag spike. The transition is then forced, ignoring the cooldown. Kept well inside the
     * 64 blocks of void a player survives below the world floor, because a band that uses the full
     * world height has nothing but that void underneath it.
     */
    private static final int FAILSAFE_MARGIN = 32;

    /**
     * 20 ticks. Long enough to keep a player from bouncing between two worlds, short enough to not
     * be noticeable while falling.
     */
    private static final long TELEPORT_COOLDOWN_MS = 1000;

    /**
     * How far a player has to rise above their world's build limit before they are handed upwards
     * even though the world above has no terrain at that column.
     */
    private static final int FREE_ASCENT_MARGIN = 4;

    /**
     * Sentinel for {@link #highestSolidY(World, int, int)} when the whole column is empty.
     */
    private static final int NO_SOLID_BLOCK = Integer.MIN_VALUE;

    /**
     * Waiting a second before validating gives Multiverse time to load the linked worlds.
     */
    private static final long LAYOUT_VALIDATION_DELAY_TICKS = 20;

    final int yOffsetConfigEntry;

    private final boolean actionBarEnabled;
    private final boolean linkedWorldsActive;

    private final Plugin plugin;
    private final HashMap<String, Integer> worldHashMap;
    private final LinkedWorldLayout layout;
    private final Cache<UUID, Boolean> teleportCooldowns = CacheBuilder.newBuilder()
            .expireAfterWrite(TELEPORT_COOLDOWN_MS, TimeUnit.MILLISECONDS)
            .build();

    public PlayerMoveEvent(@org.jspecify.annotations.NonNull Plugin plugin) {
        this.plugin = plugin;
        yOffsetConfigEntry = plugin.getConfig().getInt(Properties.Y_OFFSET, 0);
        actionBarEnabled = plugin.getConfig().getBoolean(Properties.ACTIONBAR_HEIGHT);

        String linkedWorldsMethod = plugin.getConfig().getString(Properties.LINKED_WORLDS_METHOD);
        linkedWorldsActive = plugin.getConfig().getBoolean(Properties.LINKED_WORLDS_ENABLED)
                && linkedWorldsMethod != null
                && linkedWorldsMethod.equalsIgnoreCase(Properties.NonConfigurable.METHOD_MV);

        worldHashMap = new HashMap<>();
        layout = LinkedWorldLayout.fromConfig();
        if (linkedWorldsActive) {
            List<LinkedWorld> worlds = ConfigurationHelper.getWorlds();
            for (LinkedWorld world : worlds) {
                this.worldHashMap.put(world.getWorldName(), world.getOffset());
            }
            plugin.getComponentLogger().info("Linked worlds enabled, using Multiverse method.");
            Bukkit.getScheduler().runTaskLater(plugin, () -> layout.validate(plugin), LAYOUT_VALIDATION_DELAY_TICKS);
        }
        if (actionBarEnabled) startKeepActionBarAlive();
    }

    @EventHandler(ignoreCancelled = true)
    void onPlayerMove(org.bukkit.event.player.@NonNull PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (actionBarEnabled) setHeightInActionBar(player);

        if (!this.linkedWorldsActive) return;

        // Transitions only ever happen on a block boundary, so skip the vast majority of move events.
        // Horizontal steps count as well: a world clips terrain that reaches past its build limit, so
        // the boundary is walkable as a flat plateau and crossing into a column that continues in the
        // world above never changes the player's height.
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockY() == to.getBlockY()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        handleWorldTransition(player, to);
    }

    private void startKeepActionBarAlive() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                setHeightInActionBar(p);
            }
        }, 0, 20);
    }

    private void setHeightInActionBar(@NonNull Player p) {
        worldHashMap.putIfAbsent(p.getWorld().getName(), yOffsetConfigEntry);
        if (p.getInventory().getItemInMainHand().getType() != Material.DEBUG_STICK) {
            int height = p.getLocation().getBlockY() - worldHashMap.get(p.getWorld().getName());
            p.sendActionBar(Component.text(height + "m").decorate(TextDecoration.BOLD));
        }
    }

    /**
     * Moves the player into the world above or below once they leave the usable band of their
     * current world. Both boundaries come from {@link LinkedWorldLayout}. A world's band is not
     * assumed to start at {@code mcY 0}, so a world may cover its entire height range.
     */
    private void handleWorldTransition(@NonNull Player player, @NonNull Location to) {
        if (!Permission.AUTOTELEPORT.isGrantedTo(player)) return;

        World current = player.getWorld();
        int index = layout.indexOf(current.getName());
        if (index == LinkedWorldLayout.NOT_LINKED) return;

        int y = to.getBlockY(); // the player's feet

        if (layout.hasNext(index) && y >= LinkedWorldLayout.upwardThreshold(current)) {
            if (isOnTeleportCooldown(player)) return;
            transitionUp(player, to, index, current);
            return;
        }

        if (!layout.hasPrevious(index)) return;

        World below = resolve(player, layout.get(index - 1));
        if (below == null) return;

        int threshold = layout.downwardThreshold(index, below);
        if (y >= threshold) return;

        // Below the band there is nothing left to stand on, so a missed trigger has to be caught
        // even while the cooldown is still running.
        boolean failsafe = y <= threshold - FAILSAFE_MARGIN;
        if (!failsafe && isOnTeleportCooldown(player)) return;

        transitionDown(player, to, index, below, failsafe);
    }

    /**
     * Upwards the player usually enters the target world right at the seam of a mountain that was
     * clipped off by the current world's build limit, so they are placed on top of the target column
     * rather than at the converted height.
     * <p>
     * The transition is called off when the world above turns out to have no terrain at that column:
     * the player is then simply standing on top of their own world's terrain, and handing them over
     * would drop them straight back through the boundary - the bouncing this used to cause is why
     * the decision is made after the column scan rather than before it. Only a player who has risen
     * {@link #FREE_ASCENT_MARGIN} blocks clear of the boundary is moved up regardless, since they
     * are flying rather than walking and everything above belongs to the world above.
     */
    private void transitionUp(@NonNull Player player, @NonNull Location from, int index, @NonNull World current) {
        World target = resolve(player, layout.get(index + 1));
        if (target == null) return;

        // Where the boundary elevation lands in the target world - the bottom of its usable band.
        int bandBottom = layout.downwardThreshold(index + 1, current);
        int convertedY = layout.convertY(from.getBlockY(), index, index + 1);
        boolean clearOfBoundary = from.getBlockY() >= LinkedWorldLayout.upwardThreshold(current) + FREE_ASCENT_MARGIN;

        setTeleportCooldown(player);
        Motion motion = Motion.of(player);
        int blockX = from.getBlockX();
        int blockZ = from.getBlockZ();

        plugin.getComponentLogger().debug("Moving {} up from '{}' (y {}) into '{}'",
                player.getName(), player.getWorld().getName(), from.getBlockY(), target.getName());

        // Preload the target chunk - the column scan below needs its blocks.
        target.getChunkAtAsync(blockX >> 4, blockZ >> 4).thenAccept(chunk -> onMainThread(() -> {
            int surfaceY = highestSolidY(target, blockX, blockZ);

            if (surfaceY < bandBottom && !clearOfBoundary) {
                plugin.getComponentLogger().debug("Keeping {} in '{}': '{}' has no terrain above y {} at {}/{}",
                        player.getName(), current.getName(), target.getName(), bandBottom, blockX, blockZ);
                // Nothing happened, so the next move event should be allowed to try again right away.
                clearTeleportCooldown(player);
                return;
            }

            // Never below the converted height: everything the target world holds under its band is a
            // duplicate of terrain the player has just left, and dropping them onto it would undo
            // their climb. A column that is empty up there keeps their elevation exactly.
            int y = Math.max(convertedY, surfaceY + 1);
            completeTransition(player, target, from, freeSpaceAt(target, blockX, y, blockZ), motion);
        }));
    }

    /**
     * Downwards the target world's terrain covers the elevation the player is falling through, so
     * the height is converted directly and only corrected if it would place them inside a block.
     */
    private void transitionDown(@NonNull Player player, @NonNull Location from, int index, @NonNull World target,
                                boolean failsafe) {
        setTeleportCooldown(player);
        Motion motion = Motion.of(player);
        int blockX = from.getBlockX();
        int blockZ = from.getBlockZ();
        int convertedY = layout.convertY(from.getBlockY(), index, index - 1);

        plugin.getComponentLogger().debug("Moving {} down from '{}' (y {}) into '{}' at y {}{}",
                player.getName(), player.getWorld().getName(), from.getBlockY(), target.getName(), convertedY,
                failsafe ? " (failsafe)" : "");

        target.getChunkAtAsync(blockX >> 4, blockZ >> 4).thenAccept(chunk -> onMainThread(() ->
                completeTransition(player, target, from, freeSpaceAt(target, blockX, convertedY, blockZ), motion)));
    }

    /**
     * X and Z are carried over unchanged. {@code terrain_offset.x/z} is a generation offset that
     * applies to every linked world alike, so adding it here would shift the player sideways on
     * every transition.
     */
    private void completeTransition(@NonNull Player player, @NonNull World target, @NonNull Location from,
                                    int y, @NonNull Motion motion) {
        Location destination = new Location(target, from.getX(), y, from.getZ(), from.getYaw(), from.getPitch());
        player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
            if (!Boolean.TRUE.equals(success)) return;
            onMainThread(() -> {
                motion.restoreOn(player);
                player.sendMessage(plugin.getConfig().getString(Properties.CHAT_PREFIX) + "§7You have been teleported to another world.");
            });
        });
    }

    /**
     * How the player was moving when the transition was triggered. A teleport drops the player out
     * of flight and clears their motion, so both have to be put back afterward (but only as they
     * were: forcing flight on made anyone with flight permission take off after simply walking
     * across a world boundary).
     */
    private record Motion(@NonNull Vector velocity, boolean flying) {

        static @NonNull Motion of(@NonNull Player player) {
            return new Motion(player.getVelocity(), player.isFlying());
        }

        void restoreOn(@NonNull Player player) {
            player.setVelocity(this.velocity);
            if (this.flying && player.getAllowFlight()) {
                player.setFlying(true);
            }
        }
    }

    private @Nullable World resolve(@NonNull Player player, @NonNull LinkedWorld world) {
        World bukkitWorld = Bukkit.getWorld(world.getWorldName());
        if (bukkitWorld == null) {
            plugin.getComponentLogger().warn("Cannot move {} into linked world '{}': the world is not loaded.",
                    player.getName(), world.getWorldName());
        }
        return bukkitWorld;
    }

    /**
     * Paper completes both {@code getChunkAtAsync} and {@code teleportAsync} on the server thread,
     * but block access and {@link Player#setVelocity(Vector)} would blow up if that ever changed.
     */
    private void onMainThread(@NonNull Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    /**
     * @return the highest solid block in the column, or {@link #NO_SOLID_BLOCK} if there is none.
     * Unlike {@link World#getHighestBlockYAt(int, int)} this ignores non-solid blocks and reports
     * empty columns instead of returning the world's minimum height.
     */
    private static int highestSolidY(@NonNull World world, int x, int z) {
        int start = Math.clamp(world.getHighestBlockYAt(x, z), world.getMinHeight(), world.getMaxHeight() - 1);
        for (int y = start; y >= world.getMinHeight(); y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) return y;
        }
        return NO_SOLID_BLOCK;
    }

    /**
     * Moves the given height up until the player fits, so a transition can never drop somebody into
     * terrain. Needed in both directions: the world below reaches all the way up to its own terrain
     * limit, so a mountain can occupy exactly the converted height.
     */
    private static int freeSpaceAt(@NonNull World world, int x, int y, int z) {
        int min = world.getMinHeight() + 1;
        int max = world.getMaxHeight();
        int candidate = Math.clamp(y, min, max);
        while (candidate < max && !fitsAt(world, x, candidate, z)) {
            candidate++;
        }
        return candidate;
    }

    private static boolean fitsAt(@NonNull World world, int x, int y, int z) {
        return isPassableAt(world, x, y, z) && isPassableAt(world, x, y + 1, z);
    }

    /**
     * Treats everything outside the world's height range as free space: a player may stand above the
     * build limit even though no block can exist there, and {@link World#getBlockAt(int, int, int)}
     * has no meaningful answer for those heights.
     */
    private static boolean isPassableAt(@NonNull World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return true;
        return world.getBlockAt(x, y, z).isPassable();
    }

    private boolean isOnTeleportCooldown(@NonNull Player player) {
        return teleportCooldowns.getIfPresent(player.getUniqueId()) != null;
    }

    private void setTeleportCooldown(@NonNull Player player) {
        teleportCooldowns.put(player.getUniqueId(), Boolean.TRUE);
    }

    private void clearTeleportCooldown(@NonNull Player player) {
        teleportCooldowns.invalidate(player.getUniqueId());
    }

}
