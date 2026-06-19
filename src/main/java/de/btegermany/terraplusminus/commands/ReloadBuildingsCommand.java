package de.btegermany.terraplusminus.commands;

import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.gen.RealWorldGenerator;
import de.btegermany.terraplusminus.gen.swiss.SwissBuildingBaker;
import de.btegermany.terraplusminus.gen.swiss.SwissTLM3DDataset;
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

        // Reload 3D dataset
        plugin.reloadBuildingDataset();

        // Reload footprint dataset on all loaded RealWorldGenerators
        int reloadedWorlds = 0;
        List<World> worlds = plugin.getServer().getWorlds();
        for (World world : worlds) {
            ChunkGenerator gen = world.getGenerator();
            if (gen instanceof RealWorldGenerator realGen) {
                GeneratorDatasets datasets = realGen.getDatasets();
                if (datasets == null) continue;

                Object rawDataset = datasets.getCustom(SwissBuildingBaker.KEY_DATASET_SWISS_BUILDINGS, null);
                if (!(rawDataset instanceof SwissTLM3DDataset swissDataset)) continue;

                swissDataset.invalidateCache();
                reloadedWorlds++;
            }
        }

        sender.sendMessage(prefix + "§aReloaded Swiss buildings datasets. (3D + " + reloadedWorlds + " world footprint caches)");
    }
}
