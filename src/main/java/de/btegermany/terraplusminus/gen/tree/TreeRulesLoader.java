package de.btegermany.terraplusminus.gen.tree;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.btegermany.terraplusminus.gen.CustomBiomeProvider;
import de.btegermany.terraplusminus.gen.tree.TreeRules.TreeRule;
import de.btegermany.terraplusminus.gen.tree.TreeRules.TypeChoice;
import net.buildtheearth.terraminusminus.TerraConstants;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.block.Biome;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.daporkchop.lib.common.util.PValidation.checkState;

/**
 * Reads and validates {@code tree_rules.json5}.
 * If the file is missing or invalid, the default rules bundled in the jar are used.
 */
public final class TreeRulesLoader {

    static final String DEFAULT_RULES_RESOURCE = "/de/btegermany/terraplusminus/config/tree_rules.json5";

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("rules", "height_tolerance_percent", "max_slope_degrees");
    private static final Set<String> RULE_KEYS = Set.of("name", "when", "broadleaf", "conifer", "broadleaf_percent");
    private static final Set<String> WHEN_KEYS = Set.of("biome");
    private static final double DEFAULT_BROADLEAF_PERCENT = 50;
    private static final double DEFAULT_HEIGHT_TOLERANCE_PERCENT = 20;

    private static final JsonMapper MAPPER = TerraConstants.JSON_MAPPER.rebuild()
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES, JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .build();

    /**
     * Loads the tree rules from a file, or the default rules from the jar if the file is missing or invalid.
     *
     * @param file           the rule file
     * @param templateCounts number of templates per tree type, from customTrees.json
     * @param logger         where errors and warnings are reported
     */
    public static @NonNull TreeRules loadOrDefault(
            @NonNull Path file,
            @NonNull Map<String, Integer> templateCounts,
            @NonNull ComponentLogger logger
    ) {
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                TreeRules rules = parse(MAPPER.readTree(reader), templateCounts, logger);
                logger.info("Loaded {} tree rules from {}", rules.size(), file);
                return rules;
            } catch (IOException | RuntimeException e) {
                logger.error("Invalid tree rules file {}, using the default tree rules instead.", file);
                return loadDefault(templateCounts, logger);
            }
        }
        return loadDefault(templateCounts, logger);
    }

    private static @NonNull TreeRules loadDefault(
            @NonNull Map<String, Integer> templateCounts,
            @NonNull ComponentLogger logger
    ) {
        try (InputStream in = TreeRulesLoader.class.getResourceAsStream(DEFAULT_RULES_RESOURCE)) {
            checkState(in != null, "Missing internal resource: %s", DEFAULT_RULES_RESOURCE);
            TreeRules rules = parse(MAPPER.readTree(in), templateCounts, logger);
            logger.info("Loaded {} default tree rules", rules.size());
            return rules;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load the default tree rules", e);
        }
    }

    static @NonNull TreeRules parse(
            @Nullable JsonNode root,
            @NonNull Map<String, Integer> templateCounts,
            @NonNull ComponentLogger logger
    ) {
        require(root != null && root.isObject());
        checkKeys(root, TOP_LEVEL_KEYS);

        JsonNode rulesNode = root.get("rules");
        require(rulesNode != null && rulesNode.isArray() && !rulesNode.isEmpty());

        List<TreeRule> rules = new ArrayList<>();
        for (JsonNode ruleNode : rulesNode) {
            rules.add(parseRule(ruleNode, templateCounts, logger));
        }

        // The last rule is the catch-all for everywhere no other rule matches
        require(!rules.getLast().hasConditions());
        double heightTolerancePercent = DEFAULT_HEIGHT_TOLERANCE_PERCENT;
        if (root.has("height_tolerance_percent")) {
            JsonNode tolerance = root.get("height_tolerance_percent");
            require(tolerance.isNumber() && tolerance.asDouble() >= 0);
            heightTolerancePercent = tolerance.asDouble();
        }
        double maxSlopeDegrees = Double.NaN;
        if (root.has("max_slope_degrees")) {
            JsonNode slope = root.get("max_slope_degrees");
            require(slope.isNumber() && slope.asDouble() > 0 && slope.asDouble() <= 90);
            maxSlopeDegrees = slope.asDouble();
        }
        return new TreeRules(rules, heightTolerancePercent, maxSlopeDegrees);
    }

    private static @NonNull TreeRule parseRule(
            @NonNull JsonNode node,
            @NonNull Map<String, Integer> templateCounts,
            @NonNull ComponentLogger logger
    ) {
        require(node.isObject());
        checkKeys(node, RULE_KEYS);

        String name = null;
        if (node.has("name")) {
            require(node.get("name").isTextual());
            name = node.get("name").asText();
        }

        Set<Biome> biomes = null;
        JsonNode when = node.get("when");
        if (when != null) {
            require(when.isObject());
            checkKeys(when, WHEN_KEYS);
            if (when.has("biome")) {
                biomes = parseBiomes(when.get("biome"));
            }
        }

        double broadleafPercent = DEFAULT_BROADLEAF_PERCENT;
        if (node.has("broadleaf_percent")) {
            JsonNode percent = node.get("broadleaf_percent");
            require(percent.isNumber() && percent.asDouble() >= 0 && percent.asDouble() <= 100);
            broadleafPercent = percent.asDouble();
        }

        return new TreeRule(
                name,
                biomes,
                parseTypes(node.get("broadleaf"), templateCounts, logger),
                parseTypes(node.get("conifer"), templateCounts, logger),
                broadleafPercent
        );
    }

    private static @NonNull Set<Biome> parseBiomes(@NonNull JsonNode node) {
        Set<Biome> biomes = new HashSet<>();
        for (String name : textList(node)) {
            Biome biome = CustomBiomeProvider.parseBiome(name);
            require(biome != null);
            biomes.add(biome);
        }
        return biomes;
    }

    /**
     * Parses tree types given either as a list (all templates of these types pooled)
     * or as an object of weights per type. Types without templates are reported and ignored.
     */
    private static @NonNull TypeChoice parseTypes(
            @Nullable JsonNode node,
            @NonNull Map<String, Integer> templateCounts,
            @NonNull ComponentLogger logger
    ) {
        if (node == null || node.isNull()) {
            return TypeChoice.NONE;
        }
        List<String> types = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        if (node.isArray()) {
            for (String type : textList(node)) {
                int count = templateCounts.getOrDefault(type, 0);
                if (count == 0) {
                    warnUnknownType(logger, type);
                    continue;
                }
                types.add(type);
                weights.add((double) count);
            }
        } else {
            require(node.isObject());
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                JsonNode weight = entry.getValue();
                require(weight.isNumber() && weight.asDouble() > 0);
                if (templateCounts.getOrDefault(entry.getKey(), 0) == 0) {
                    warnUnknownType(logger, entry.getKey());
                    continue;
                }
                types.add(entry.getKey());
                weights.add(weight.asDouble());
            }
        }

        if (types.isEmpty()) {
            return TypeChoice.NONE;
        }
        double[] cumulativeWeights = new double[weights.size()];
        double sum = 0;
        for (int i = 0; i < weights.size(); i++) {
            sum += weights.get(i);
            cumulativeWeights[i] = sum;
        }
        return new TypeChoice(List.copyOf(types), cumulativeWeights);
    }

    private static void warnUnknownType(@NonNull ComponentLogger logger, @NonNull String type) {
        logger.warn("Tree rules: unknown tree type '{}' (no templates in customTrees.json), ignoring it", type);
    }

    private static @NonNull List<String> textList(@NonNull JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isTextual()) {
            values.add(node.asText());
            return values;
        }
        require(node.isArray());
        for (JsonNode element : node) {
            require(element.isTextual());
            values.add(element.asText());
        }
        return values;
    }

    private static void checkKeys(@NonNull JsonNode node, @NonNull Set<String> allowed) {
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            require(allowed.contains(it.next()));
        }
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("invalid tree rules");
        }
    }
}
