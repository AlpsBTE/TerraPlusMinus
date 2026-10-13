package de.btegermany.terraplusminus.gen.landcover;

import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// TODO: move to T--
public enum LandCoverType {
    WATER,
    FOREST,
    GRASSLAND,
    CROPLAND,
    WETLAND,
    URBAN,
    BARE_ROCK,
    SCREE,
    SHINGLE,
    SAND,
    GLACIER,
    SNOW,
    UNKNOWN;

    private static final Map<String, LandCoverType> BY_NAME = new HashMap<>();

    static {
        for (LandCoverType type : values()) {
            BY_NAME.put(type.name(), type);
        }
    }

    public static @NonNull LandCoverType fromName(String name) {
        if (name == null || name.isBlank()) return UNKNOWN;
        LandCoverType type = BY_NAME.get(name.trim().toUpperCase(Locale.ROOT));
        return type != null ? type : UNKNOWN;
    }
}
