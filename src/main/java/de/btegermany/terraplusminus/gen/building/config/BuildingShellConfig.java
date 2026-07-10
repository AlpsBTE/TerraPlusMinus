package de.btegermany.terraplusminus.gen.building.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

public record BuildingShellConfig(
        String id,
        boolean enabled,
        double priority,
        DatasetExtent bounds,
        Path path,
        String material,
        double searchRadius
) {
    public static BuildingShellConfig fromJson(JsonNode node, Path pluginDataPath) {
        String id = requiredText(node, "id");
        return new BuildingShellConfig(
                id,
                optionalBoolean(node, "enabled", true),
                optionalDouble(node, "priority", 0.0d),
                DatasetExtent.fromJson(requiredObject(node, "bounds")),
                pluginDataPath.resolve(requiredText(node, "path")),
                optionalText(node, "material", "minecraft:stone"),
                optionalDouble(node, "searchRadius", 10.0d)
        );
    }

    private static JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Missing object field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Missing text field: " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static boolean optionalBoolean(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static double optionalDouble(JsonNode node, String field, double fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }
}
