package de.btegermany.terraplusminus.events;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jspecify.annotations.NonNull;

public class PlayerCommandEvent implements Listener {
    private final String pluginName;

    public PlayerCommandEvent(String pluginName) {
        this.pluginName = pluginName;
    }

    @EventHandler
    public void onCommand(@NonNull PlayerCommandPreprocessEvent command) {
        if (command.getMessage().startsWith("/tpll")) {
            command.setMessage(pluginName + ":" + command.getMessage().substring(1));
        }
    }
}
