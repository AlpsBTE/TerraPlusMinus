package de.btegermany.terraplusminus.gen.building.config;

import static de.btegermany.terraplusminus.gen.building.config.JsonFields.optionalBoolean;
import static de.btegermany.terraplusminus.gen.building.config.JsonFields.optionalDouble;
import static de.btegermany.terraplusminus.gen.building.config.JsonFields.optionalText;
import static de.btegermany.terraplusminus.gen.building.config.JsonFields.requiredObject;
import static de.btegermany.terraplusminus.gen.building.config.JsonFields.requiredText;

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
}
