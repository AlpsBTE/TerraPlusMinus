package de.btegermany.terraplusminus.gen.landcover;

import com.fasterxml.jackson.databind.JsonNode;
import de.btegermany.terraplusminus.Terraplusminus;
import lombok.NonNull;
import net.buildtheearth.terraminusminus.TerraConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LandCoverDatasetConfigLoader {
    public static final String CONFIG_FILE = "land_cover.json5";

    private LandCoverDatasetConfigLoader() {
    }

    public static LandCoverConfig load(@NonNull Terraplusminus plugin) {
        Path path = plugin.getDataPath().resolve(CONFIG_FILE);
        if (!Files.exists(path)) return new LandCoverConfig(false, List.of());

        try {
            JsonNode root = TerraConstants.JSON_MAPPER.readTree(path.toFile());
            if (root == null || root.isNull() || !root.isObject()) {
                plugin.getComponentLogger().warn("Land cover config '{}' must contain a top-level object.", path);
                return new LandCoverConfig(false, List.of());
            }

            boolean enabled = optionalBoolean(root, "enabled", true);
            List<LandCoverDatasetConfig> datasets = new ArrayList<>();

            JsonNode datasetsNode = root.get("datasets");
            if (datasetsNode != null && datasetsNode.isArray()) {
                for (JsonNode node : datasetsNode) {
                    try {
                        datasets.add(parseDataset(node, plugin));
                    } catch (RuntimeException e) {
                        plugin.getComponentLogger().warn("Skipping invalid land-cover dataset entry in '{}': {}", path, e.getMessage());
                    }
                }
            }

            plugin.getComponentLogger().info("Loaded {} land-cover dataset(s) from '{}'", datasets.size(), path);
            return new LandCoverConfig(enabled, datasets);
        } catch (IOException e) {
            plugin.getComponentLogger().warn("Failed to read land-cover config '{}'.", path, e);
            return new LandCoverConfig(false, List.of());
        }
    }

    private static LandCoverDatasetConfig parseDataset(@NonNull JsonNode node, @NonNull Terraplusminus plugin) {
        String id = requiredText(node, "id");
        boolean enabled = optionalBoolean(node, "enabled", true);
        if (!enabled) throw new IllegalArgumentException("dataset '" + id + "' is disabled");

        double priority = optionalDouble(node, "priority", 0.0d);
        String type = requiredText(node, "type");
        if (!"vector_geojson".equals(type)) {
            throw new IllegalArgumentException("dataset '" + id + "' has unsupported type '" + type + "'");
        }

        Path tileDirectory = plugin.getDataPath().resolve(requiredText(node, "path"));
        if (!Files.exists(tileDirectory)) {
            throw new IllegalArgumentException("dataset '" + id + "' directory '" + tileDirectory + "' does not exist");
        }

        double tileSizeDegrees = optionalDouble(node, "tile_size_degrees", 1.0d / 32.0d);
        String propertyName = optionalText(node, "property", "land_cover");

        return new LandCoverDatasetConfig(id, tileDirectory, tileSizeDegrees, priority, propertyName);
    }

    public record LandCoverConfig(boolean enabled, @NonNull List<LandCoverDatasetConfig> datasets) {
    }

    public record LandCoverDatasetConfig(
            @NonNull String id,
            @NonNull Path tileDirectory,
            double tileSizeDegrees,
            double priority,
            @NonNull String propertyName
    ) {
    }

    private static String requiredText(@NonNull JsonNode node, @NonNull String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("Missing text field: " + field);
        return value.asText();
    }

    private static String optionalText(@NonNull JsonNode node, @NonNull String field, @NonNull String fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static boolean optionalBoolean(@NonNull JsonNode node, @NonNull String field, boolean fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static double optionalDouble(@NonNull JsonNode node, @NonNull String field, double fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }
}
