package de.btegermany.terraplusminus.gen.road;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.btegermany.terraplusminus.gen.surface.NoiseSampler;
import de.btegermany.terraplusminus.gen.surface.SpeckleHash;
import de.btegermany.terraplusminus.gen.surface.SurfacePatternLayer;
import lombok.Getter;
import net.buildtheearth.terraminusminus.substitutes.BlockState;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@Getter
public final class RoadMaterialRule {
    private static final long SEED_BASE = 0x2C1B3C6D9E8F7A51L;

    private final String name;
    private final double priority;
    private final Set<RoadKind> kinds;
    private final Set<RoadClass> classes;
    private final Set<RoadSurface> surfaces;
    private final Set<String> datasets;
    private final SurfacePatternLayer mix;
    private final long seed;

    @JsonCreator
    public RoadMaterialRule(
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty("priority") Double priority,
            @JsonProperty("kind") List<String> kinds,
            @JsonProperty("class") List<String> classes,
            @JsonProperty("surface") List<String> surfaces,
            @JsonProperty("dataset") List<String> datasets,
            @JsonProperty(value = "mix", required = true) SurfacePatternLayer mix
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a road material rule must have a non-empty 'name'");
        }
        this.name = name;
        this.priority = priority == null ? 0.0d : priority;
        this.kinds = parseEnumSet(name, "kind", kinds, RoadKind.class, RoadKind::fromName);
        this.classes = parseEnumSet(name, "class", classes, RoadClass.class, RoadClass::fromName);
        this.surfaces = parseEnumSet(name, "surface", surfaces, RoadSurface.class, RoadSurface::fromName);
        this.datasets = datasets == null ? Set.of() : Set.copyOf(new HashSet<>(datasets));
        if (mix == null) {
            throw new IllegalArgumentException("road material '" + name + "' must define a 'mix'");
        }
        if (!mix.isInfinite()) {
            throw new IllegalArgumentException("road material '" + name + "': 'depth' is not supported in a road mix");
        }
        if (mix.isNoise3d()) {
            throw new IllegalArgumentException("road material '" + name + "': 'noise_3d' is not supported in a road mix");
        }
        this.mix = mix;
        this.seed = SpeckleHash.hash(SEED_BASE, name.hashCode(), 0, 0);
    }

    public boolean matches(
            @NonNull String datasetId,
            @NonNull RoadKind kind,
            @Nullable RoadClass roadClass,
            @Nullable RoadSurface surface
    ) {
        if (!this.datasets.isEmpty() && !this.datasets.contains(datasetId)) return false;
        if (!this.kinds.isEmpty() && !this.kinds.contains(kind)) return false;
        if (!this.classes.isEmpty() && (roadClass == null || !this.classes.contains(roadClass))) return false;
        return this.surfaces.isEmpty() || (surface != null && this.surfaces.contains(surface));
    }

    public @NonNull BlockState pick(int blockX, int blockZ) {
        double mixValue = this.mix.getMode() == SurfacePatternLayer.Mode.NOISE
                ? NoiseSampler.sample2d(this.seed, this.mix.getNoiseScale(), blockX, blockZ)
                : SpeckleHash.value(this.seed, blockX, 0, blockZ);
        return this.mix.pick(mixValue).getBlock();
    }

    private static <E extends Enum<E>> Set<E> parseEnumSet(
            String rule,
            String field,
            @Nullable List<String> names,
            Class<E> type,
            Function<String, @Nullable E> parser
    ) {
        if (names == null || names.isEmpty()) return Collections.emptySet();
        Set<E> set = EnumSet.noneOf(type);
        for (String value : names) {
            E parsed = parser.apply(value);
            if (parsed == null) {
                throw new IllegalArgumentException("road material '" + rule + "': unknown " + field + " '" + value + "'");
            }
            set.add(parsed);
        }
        return Collections.unmodifiableSet(set);
    }

    @Override
    public String toString() {
        return "RoadMaterialRule{name='" + this.name + "', priority=" + this.priority
                + ", kind=" + this.kinds + ", class=" + this.classes + ", surface=" + this.surfaces
                + (this.datasets.isEmpty() ? "" : ", dataset=" + this.datasets)
                + ", mix=" + this.mix + "}";
    }
}
