package de.btegermany.terraplusminus.gen.surface;

import net.buildtheearth.terraminusminus.TerraConstants;
import net.buildtheearth.terraminusminus.substitutes.BlockState;
import net.buildtheearth.terraminusminus.substitutes.TerraBukkit;
import net.buildtheearth.terraminusminus.substitutes.exceptions.TranslateToForeignObjectException;
import net.buildtheearth.terraminusminus.util.http.Disk;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SurfacePatternLoader {
    private static final String CONFIG_FILE_NAME = "surface_patterns.json5";
    private static final String BUNDLED_RESOURCE = "/surface_patterns.json5";

    private SurfacePatternLoader() {
    }

    public static SurfacePatternsConfig load(ComponentLogger logger) {
        Path userFile = Disk.configFile(CONFIG_FILE_NAME);
        try (Reader reader = openReader(userFile)) {
            SurfacePatternsConfig config = TerraConstants.JSON_MAPPER.readValue(reader, SurfacePatternsConfig.class);
            validateBlocks(config);
            logSummary(config, userFile, logger);
            return config;
        } catch (Exception e) {
            logger.error(
                    "Failed to load {}: surface patterns are disabled and terrain will be generated with the default surface. Reason: {}",
                    CONFIG_FILE_NAME, e.getMessage(), e
            );
            return new SurfacePatternsConfig(false, SurfacePatternsConfig.DEFAULT_SLOPE_RADIUS, List.of());
        }
    }

    private static Reader openReader(Path userFile) throws IOException {
        if (Files.exists(userFile)) {
            return Files.newBufferedReader(userFile, StandardCharsets.UTF_8);
        }
        InputStream resource = SurfacePatternLoader.class.getResourceAsStream(BUNDLED_RESOURCE);
        if (resource == null) {
            throw new IllegalStateException("Missing bundled resource: " + BUNDLED_RESOURCE);
        }
        return new InputStreamReader(resource, StandardCharsets.UTF_8);
    }

    private static void validateBlocks(SurfacePatternsConfig config) {
        List<String> errors = new ArrayList<>();
        for (SurfacePattern pattern : config.getPatterns()) {
            for (SurfacePattern.Stratum stratum : pattern.getStrata()) {
                for (SurfacePatternBlock mixBlock : stratum.layer().getBlocks()) {
                    checkBlockValid(mixBlock.getBlock(), pattern, errors);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("invalid block state(s): " + String.join("; ", errors));
        }
    }

    private static void checkBlockValid(BlockState state, SurfacePattern pattern, List<String> errors) {
        try {
            TerraBukkit.toBukkitBlockData(state);
        } catch (TranslateToForeignObjectException e) {
            errors.add("pattern '" + pattern.displayName() + "': " + e.getMessage());
        }
    }

    private static void logSummary(SurfacePatternsConfig config, Path userFile, ComponentLogger logger) {
        String source = Files.exists(userFile) ? userFile.toString() : "bundled default";
        logger.info(
                "Loaded {} surface pattern(s) from {} (enabled: {}, slope radius: {})",
                config.getPatterns().size(), source, config.isEnabled(), config.getSlopeRadius()
        );
        for (SurfacePattern pattern : config.getPatterns()) {
            logger.debug("Surface pattern: {}", pattern);
        }
        if (config.isEnabled() && config.getPatterns().isEmpty()) {
            logger.warn("Surface patterns are enabled, but no patterns are defined in {}", CONFIG_FILE_NAME);
        }
    }
}
