package de.btegermany.terraplusminus.commands;

import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.gen.RealWorldGenerator;
import de.btegermany.terraplusminus.gen.building.outline.MultiBuildingOutlineDataset;
import de.btegermany.terraplusminus.utils.Properties;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import net.buildtheearth.terraminusminus.generator.GeneratorDatasets;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

public class ReloadBuildingsCommand implements BasicCommand {

    public static final String PERMISSION = "t+-.reloadbuildings";
    private final Terraplusminus plugin;

    public ReloadBuildingsCommand(Terraplusminus plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(
        @NotNull CommandSourceStack stack,
        @NotNull String @NonNull [] args
    ) {
        CommandSender sender = stack.getSender();
        String prefix = plugin.getConfig().getString(Properties.CHAT_PREFIX, "§2§lT+- §8» ");

        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(prefix + "§cNo permission for /reloadbuildings");
            return;
        }

        plugin.reloadBuildingDataset();

        int reloadedWorlds = 0;
        List<World> worlds = plugin.getServer().getWorlds();
        for (World world : worlds) {
            ChunkGenerator gen = world.getGenerator();
            if (gen instanceof RealWorldGenerator realGen) {
                GeneratorDatasets datasets = realGen.getDatasets();
                if (datasets == null) continue;

                Object rawDataset = datasets.getCustom(MultiBuildingOutlineDataset.KEY, null);
                if (!(rawDataset instanceof MultiBuildingOutlineDataset outlineDataset)) continue;

                outlineDataset.invalidateCache();
                reloadedWorlds++;
            }
        }

        sender.sendMessage(prefix + "§aReloaded building datasets. (shells + " + reloadedWorlds + " world outline caches)");
    }
}
