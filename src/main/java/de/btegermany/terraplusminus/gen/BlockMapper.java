package de.btegermany.terraplusminus.gen;

import net.buildtheearth.terraminusminus.substitutes.BlockState;
import net.buildtheearth.terraminusminus.substitutes.Identifier;
import net.buildtheearth.terraminusminus.substitutes.TerraBukkit;
import net.buildtheearth.terraminusminus.util.http.Disk;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

import static net.buildtheearth.terraminusminus.substitutes.TerraBukkit.toBukkitBlockData;

/**
 * Maps Terra-- {@link BlockState block states} to Bukkit {@link BlockData block datas},
 * based on material replacements configured in a {@link FileConfiguration}.
 * <br>
 * This purposefully only supports materials to be defined in the config and not full block data,
 * in order to maintain exact backward compatibility,
 * and encourage the use of <code>osm.json5</code> instead for complex use cases.
 *
 * @author Smyler
 */
public class BlockMapper {

    private final BlockData genericSurfaceBlock;
    private final Map<Identifier, BlockData> mapping;

    private BlockMapper(BlockData genericSurfaceBlock, Map<Identifier, BlockData> mapping) {
        this.genericSurfaceBlock = genericSurfaceBlock;
        this.mapping = mapping;
    }

    /**
     * Maps a {@link BlockState block state} to a {@link BlockData block data} using the defined mappings.
     * {@code null} {@link BlockState block states} are mapped to {@code null} {@link BlockData}.
     * If there is no explicit mapping for the {@link BlockState block state}'s material,
     * it is natively converted to a {@link BlockData block data} with Terra--'s {@link TerraBukkit#toBukkitBlockData(BlockState)}.
     *
     * @param blockState the {@link BlockState} to map to a {@link BlockData}
     * @return the mapped {@link BlockData}, or {@code null}
     */
    @Contract(value = "null -> null; !null -> !null", pure = true)
    public @Nullable BlockData map(@Nullable BlockState blockState) {
        if (blockState == null) {
            return null;
        }
        BlockData data = this.mapping.get(blockState.getBlock());
        if (data != null) {
            return data;
        }
        return toBukkitBlockData(blockState);
    }

    public @Nullable BlockData genericSurfaceBlock() {
        return this.genericSurfaceBlock;
    }

    public static @NonNull Builder fromPlugin(@NonNull Plugin plugin) {
        return new Builder(plugin.getConfig(), plugin.getComponentLogger());
    }

    public static class Builder {
        private final FileConfiguration configuration;
        private final ComponentLogger logger;
        private BlockData genericSurfaceBlock;
        private final Map<Identifier, BlockData> mapping = new HashMap<>();

        private Builder(FileConfiguration config, ComponentLogger logger) {
            this.configuration = config;
            this.logger = logger;
        }

        public Builder withConfiguredMapping(@NonNull Identifier materialId, @NonNull String configPath) {
            Material material = this.readMaterialFromConfig(configPath);
            if (material == null) {
                return this;
            }
            this.logger.warn(
                    "Configuration entry {} is set to {}, but is deprecated and may be removed in future versions. " +
                    "Consider removing it from your configuration and editing {} instead.",
                    configPath,
                    material,
                    Disk.configFile("osm.json5")
            );
            return this.withStaticMapping(materialId, material);
        }

        public Builder withConfiguredGenericSurface(@NonNull String configPath) {
            Material material = this.readMaterialFromConfig(configPath);
            if (material == null) {
                return this;
            }
            return this.withStaticGenericSurface(material);
        }

        public Builder withStaticGenericSurface(@NonNull Material material) {
            this.genericSurfaceBlock = material.createBlockData();
            return this;
        }

        public Builder withConfiguredMapping(@NonNull String materialId, @NonNull String configPath) {
            Identifier identifier = Identifier.parse(materialId);  // Let this fail if invalid, that means devs are at fault
            return this.withConfiguredMapping(identifier, configPath);
        }

        public Builder withStaticMapping(@NonNull Identifier materialId, @NonNull Material material) {
            this.logger.trace(
                    "Adding material replacement mapping {} -> {}",
                    materialId, material
            );
            this.mapping.put(materialId, material.createBlockData());
            return this;
        }

        private @Nullable Material readMaterialFromConfig(@NonNull String configPath) {
            String materialName = this.configuration.getString(configPath);
            if (materialName == null) {
                return null;
            }
            Material material = Material.getMaterial(materialName);
            if (material == null) {
                this.logger.warn(
                        "Configuration entry '{}' has been explicitly set to '{}', but no such material exists. It will be ignored.",
                        configPath, materialName
                );
                return null;
            }
            return material;
        }

        public @NonNull BlockMapper build() {
            return new BlockMapper(this.genericSurfaceBlock, this.mapping);
        }
    }

}
