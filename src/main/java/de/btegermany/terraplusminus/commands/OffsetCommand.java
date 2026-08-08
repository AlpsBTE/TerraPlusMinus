package de.btegermany.terraplusminus.commands;

import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.utils.ConfigurationHelper;
import de.btegermany.terraplusminus.utils.Permission;
import de.btegermany.terraplusminus.utils.Properties;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class OffsetCommand implements BasicCommand {

    @Override
    public boolean canUse(final @NonNull CommandSender sender) {
        return sender instanceof Player && Permission.OFFSET_CMD.isGrantedTo(sender);
    }

    @Override
    public void execute(@NonNull CommandSourceStack stack, String @Nullable [] args) {
        if (!(stack.getSender() instanceof Player player)) return; // Will not happen because of Brigadier

        player.sendMessage(Terraplusminus.config.getString("prefix") + "§7Offsets:");
        player.sendMessage(Terraplusminus.config.getString("prefix") + "§7 | X: §8" + Terraplusminus.config.getInt("terrain_offset.x"));

        if (!Terraplusminus.config.getString("linked_worlds.method", Properties.NonConfigurable.METHOD_MV).equalsIgnoreCase(Properties.NonConfigurable.METHOD_MV) || !Terraplusminus.config.getBoolean("linked_worlds.enabled")) {
            player.sendMessage(Terraplusminus.config.getString("prefix") + "§7 | Y: §8" + Terraplusminus.config.getInt("terrain_offset.y"));
        } else {
            if (Terraplusminus.config.getBoolean("linked_worlds.enabled") && Terraplusminus.config.getString("linked_worlds.method", Properties.NonConfigurable.METHOD_MV).equalsIgnoreCase(Properties.NonConfigurable.METHOD_MV)) {
                ConfigurationHelper.getWorlds().forEach(world -> player.sendMessage(Terraplusminus.config.getString("prefix") + "§9 " + world.getWorldName() + "§7 | Y: §8" + world.getOffset()));
            }
        }

        player.sendMessage(Terraplusminus.config.getString("prefix") + "§7 | Z: §8" + Terraplusminus.config.getInt("terrain_offset.z"));
    }
}
