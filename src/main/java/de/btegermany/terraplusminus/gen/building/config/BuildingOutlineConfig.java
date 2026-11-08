package de.btegermany.terraplusminus.gen.building.config;

import static de.btegermany.terraplusminus.gen.building.config.JsonFields.optionalBoolean;
import static de.btegermany.terraplusminus.gen.building.config.JsonFields.optionalDouble;
import static de.btegermany.terraplusminus.gen.building.config.JsonFields.optionalText;
import static de.btegermany.terraplusminus.gen.building.config.JsonFields.requiredObject;
import static de.btegermany.terraplusminus.gen.building.config.JsonFields.requiredText;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;

public record BuildingOutlineConfig(
        String id,
        boolean enabled,
        double priority,
        DatasetExtent bounds,
        Path path,
        double tileSizeDegrees,
        String outlineMaterial,
        String interiorMaterial
) {
    public static BuildingOutlineConfig fromJson(JsonNode node, Path pluginDataPath) {
        String id = requiredText(node, "id");
        return new BuildingOutlineConfig(
                id,
                optionalBoolean(node, "enabled", true),
                optionalDouble(node, "priority", 0.0d),
                DatasetExtent.fromJson(requiredObject(node, "bounds")),
                pluginDataPath.resolve(requiredText(node, "path")),
                optionalDouble(node, "tileSizeDegrees", 1.0d / 32.0d),
                optionalText(node, "outlineMaterial", "minecraft:stone_bricks"),
                optionalText(node, "interiorMaterial", "minecraft:clay")
        );
    }
}
