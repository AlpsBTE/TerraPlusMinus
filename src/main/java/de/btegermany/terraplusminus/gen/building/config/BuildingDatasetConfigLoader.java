package de.btegermany.terraplusminus.gen.building.config;

import de.btegermany.terraplusminus.Terraplusminus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.buildtheearth.terraminusminus.TerraConstants;

public final class BuildingDatasetConfigLoader {
    public static final String OUTLINES_CONFIG = "building_outlines.json5";
    public static final String SHELLS_CONFIG = "building_shells.json5";

    private BuildingDatasetConfigLoader() {
    }

    public static List<BuildingOutlineConfig> loadOutlines(Terraplusminus plugin) {
        return loadArray(
                plugin,
                plugin.getDataPath().resolve(OUTLINES_CONFIG),
                node -> BuildingOutlineConfig.fromJson(node, plugin.getDataPath())
        );
    }

    public static List<BuildingShellConfig> loadShells(Terraplusminus plugin) {
        return loadArray(
                plugin,
                plugin.getDataPath().resolve(SHELLS_CONFIG),
                node -> BuildingShellConfig.fromJson(node, plugin.getDataPath())
        );
    }

    private static <T> List<T> loadArray(Terraplusminus plugin, Path path, Parser<T> parser) {
        if (!Files.exists(path)) return List.of();

        try {
            var root = TerraConstants.JSON_MAPPER.readTree(path.toFile());
            if (root == null || root.isNull()) return List.of();
            if (!root.isArray()) {
                plugin.getComponentLogger().warn("Building dataset config '{}' must contain a top-level array.", path);
                return List.of();
            }

            List<T> configs = new ArrayList<>();
            for (var node : root) {
                try {
                    configs.add(parser.parse(node));
                } catch (RuntimeException e) {
                    plugin.getComponentLogger().warn("Skipping invalid building dataset entry in '{}': {}", path, e.getMessage());
                }
            }
            return configs;
        } catch (IOException e) {
            plugin.getComponentLogger().warn("Failed to read building dataset config '{}'.", path, e);
            return List.of();
        }
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(com.fasterxml.jackson.databind.JsonNode node);
    }
}
