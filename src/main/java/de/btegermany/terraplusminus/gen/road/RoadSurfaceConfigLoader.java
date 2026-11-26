package de.btegermany.terraplusminus.gen.road;

import com.fasterxml.jackson.databind.JsonNode;
import de.btegermany.terraplusminus.Terraplusminus;
import de.btegermany.terraplusminus.gen.building.config.DatasetExtent;
import de.btegermany.terraplusminus.gen.surface.SurfacePatternBlock;
import net.buildtheearth.terraminusminus.TerraConstants;
import net.buildtheearth.terraminusminus.substitutes.TerraBukkit;
import net.buildtheearth.terraminusminus.substitutes.exceptions.TranslateToForeignObjectException;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jspecify.annotations.NonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RoadSurfaceConfigLoader {
    public static final String CONFIG_FILE = "road_surfaces.json5";

    private RoadSurfaceConfigLoader() {
    }

    public static @NonNull RoadSurfaceConfig load(@NonNull Terraplusminus plugin) {
        ComponentLogger logger = plugin.getComponentLogger();
        Path path = plugin.getDataPath().resolve(CONFIG_FILE);
        if (!Files.exists(path)) return RoadSurfaceConfig.disabled();

        try {
            JsonNode root = TerraConstants.JSON_MAPPER.readTree(path.toFile());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("the file must contain a top-level object");
            }

            boolean enabled = !root.has("enabled") || root.get("enabled").asBoolean(true);

            List<RoadSurfaceConfig.DatasetConfig> datasets = new ArrayList<>();
            Set<String> datasetIds = new HashSet<>();
            JsonNode datasetsNode = root.get("datasets");
            if (datasetsNode != null && datasetsNode.isArray()) {
                for (JsonNode node : datasetsNode) {
                    try {
                        RoadSurfaceConfig.DatasetConfig dataset = parseDataset(node, plugin.getDataPath());
                        if (dataset == null) continue; // disabled
                        if (!datasetIds.add(dataset.id())) {
                            throw new IllegalArgumentException("duplicate dataset id '" + dataset.id() + "'");
                        }
                        if (!Files.isDirectory(dataset.path())) {
                            throw new IllegalArgumentException("dataset '" + dataset.id() + "' directory '" + dataset.path() + "' does not exist");
                        }
                        datasets.add(dataset);
                    } catch (RuntimeException e) {
                        logger.warn("Skipping invalid road surface dataset entry in '{}': {}", path, e.getMessage());
                    }
                }
            }

            List<RoadMaterialRule> materials = new ArrayList<>();
            JsonNode materialsNode = root.get("materials");
            if (materialsNode != null && materialsNode.isArray()) {
                for (JsonNode node : materialsNode) {
                    try {
                        RoadMaterialRule rule = TerraConstants.JSON_MAPPER.treeToValue(node, RoadMaterialRule.class);
                        validateBlocks(rule);
                        materials.add(rule);
                    } catch (Exception e) {
                        logger.warn("Skipping invalid road surface material entry in '{}': {}", path, e.getMessage());
                    }
                }
            }

            RoadSurfaceConfig config = new RoadSurfaceConfig(enabled, List.copyOf(datasets), List.copyOf(materials));
            logger.info("Loaded {} road surface dataset(s) and {} material rule(s) from '{}' (enabled: {})",
                    datasets.size(), materials.size(), path, enabled);
            for (RoadMaterialRule rule : materials) {
                logger.debug("Road surface material: {}", rule);
            }
            if (enabled && !datasets.isEmpty() && materials.isEmpty()) {
                logger.warn("Road surfaces are enabled, but no valid material rules are defined in {}: nothing will be painted", CONFIG_FILE);
            }
            return config;
        } catch (Exception e) {
            logger.error("Failed to load {}: road surfaces are disabled. Reason: {}", CONFIG_FILE, e.getMessage(), e);
            return RoadSurfaceConfig.disabled();
        }
    }

    private static RoadSurfaceConfig.DatasetConfig parseDataset(@NonNull JsonNode node, @NonNull Path dataPath) {
        String id = requiredText(node, "id");
        if (node.has("enabled") && !node.get("enabled").asBoolean(true)) return null;

        JsonNode boundsNode = node.get("bounds");
        if (boundsNode == null || !boundsNode.isObject()) {
            throw new IllegalArgumentException("dataset '" + id + "' is missing its 'bounds' object");
        }

        Set<String> keepOsmHighways = new HashSet<>();
        JsonNode keepNode = node.get("keep_osm_highways");
        if (keepNode != null && keepNode.isArray()) {
            keepNode.forEach(value -> keepOsmHighways.add(value.asText()));
        }

        double tileSizeDegrees = node.has("tile_size_degrees") ? node.get("tile_size_degrees").asDouble() : 1.0d / 32.0d;
        if (!(tileSizeDegrees > 0.0d)) {
            throw new IllegalArgumentException("dataset '" + id + "': tile_size_degrees must be positive");
        }

        return new RoadSurfaceConfig.DatasetConfig(
                id,
                node.has("priority") ? node.get("priority").asDouble() : 0.0d,
                DatasetExtent.fromJson(boundsNode),
                dataPath.resolve(requiredText(node, "path")),
                tileSizeDegrees,
                !node.has("replace_osm_roads") || node.get("replace_osm_roads").asBoolean(true),
                Set.copyOf(keepOsmHighways)
        );
    }

    private static void validateBlocks(@NonNull RoadMaterialRule rule) {
        List<String> errors = new ArrayList<>();
        for (SurfacePatternBlock block : rule.getMix().getBlocks()) {
            try {
                TerraBukkit.toBukkitBlockData(block.getBlock());
            } catch (TranslateToForeignObjectException e) {
                errors.add(e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("road material '" + rule.getName() + "': invalid block state(s): " + String.join("; ", errors));
        }
    }

    private static String requiredText(@NonNull JsonNode node, @NonNull String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing text field '" + field + "'");
        }
        return value.asText();
    }
}
