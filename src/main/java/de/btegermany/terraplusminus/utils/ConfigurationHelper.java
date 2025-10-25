package de.btegermany.terraplusminus.utils;

import de.btegermany.terraplusminus.Terraplusminus;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ConfigurationHelper {

    private ConfigurationHelper() {
        throw new IllegalStateException();
    }

    public static List<LinkedWorld> convertList(@NonNull List<Map<?, ?>> originalList) {
        return originalList.stream()
                .map(ConfigurationHelper::convertMapToLinkedWorld)
                .filter(world -> !world.getWorldName().equalsIgnoreCase("another_world/server") || !world.getWorldName().equalsIgnoreCase("current_world/server"))
                .collect(Collectors.toList());
    }

    private static @NonNull LinkedWorld convertMapToLinkedWorld(@NonNull Map<?, ?> originalMap) {
        String worldName = originalMap.get("name").toString();
        int offset = (Integer) originalMap.get("offset");
        return new LinkedWorld(worldName, offset);
    }

    /**
     * Gets the linked worlds list from config dynamically.
     * This ensures the list is always up-to-date after config reloads.
     */
    private static List<LinkedWorld> getWorldsList() {
        return convertList(Terraplusminus.instance.getConfig().getMapList("linked_worlds.worlds"));
    }

    public static @Nullable LinkedWorld getNextServerName(String currentWorldName) {
        List<LinkedWorld> worlds = getWorldsList();
        int currentIndex = -1;

        Terraplusminus.instance.getComponentLogger().debug("Searching for world: '{}' in linked worlds list", currentWorldName);
        for (LinkedWorld w : worlds) {
            Terraplusminus.instance.getComponentLogger().debug("Available world in config: '{}'", w.getWorldName());
        }

        for (int i = 0; i < worlds.size(); i++) {
            LinkedWorld world = worlds.get(i);
            if (world.getWorldName().equalsIgnoreCase(currentWorldName)) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex >= 0 && currentIndex < worlds.size() - 1) {
            return worlds.get(currentIndex + 1);
        } else {
            // Entweder wurde die Welt nicht gefunden oder sie ist die letzte Welt in der Liste
            Terraplusminus.instance.getComponentLogger().warn("World after '{}' not found in linked worlds configuration", currentWorldName);
            return null;
        }
    }

    public static @Nullable LinkedWorld getPreviousServerName(String currentWorldName) {
        List<LinkedWorld> worlds = getWorldsList();
        int currentIndex = -1;

        Terraplusminus.instance.getComponentLogger().debug("Searching for world: '{}' in linked worlds list", currentWorldName);
        for (LinkedWorld w : worlds) {
            Terraplusminus.instance.getComponentLogger().debug("Available world in config: '{}'", w.getWorldName());
        }

        for (int i = 0; i < worlds.size(); i++) {
            LinkedWorld world = worlds.get(i);
            if (world.getWorldName().equalsIgnoreCase(currentWorldName)) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex > 0) {
            return worlds.get(currentIndex - 1);
        } else {
            // Entweder wurde die Welt nicht gefunden oder sie ist die erste Welt in der Liste
            Terraplusminus.instance.getComponentLogger().warn("World after '{}' not found in linked worlds configuration", currentWorldName);
            return null;
        }
    }

    public static List<LinkedWorld> getWorlds() {
        return getWorldsList();
    }

}
