package de.btegermany.terraplusminus.utils;

import org.bukkit.plugin.java.JavaPlugin;
import org.lushplugins.pluginupdater.paper.api.PaperUpdater;

public class Updater {
    public Updater(JavaPlugin plugin) {
        PaperUpdater.builder(plugin)
                .github("BTE-Germany/TerraPlusMinus")
                .notify(true)
                .notificationMessage("<#ffe27a>A new <#e0c01b>%plugin% <#ffe27a>update is now available! " +
                        "<#e0c01b>%current_version% <#ffe27a>-> <#e0c01b>%latest_version%") // Default message is no minimsg
                .notificationPermission(Permission.UPDATENOTIFY.getNode())
                .build();
    }
}
