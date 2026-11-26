package de.btegermany.terraplusminus.gen.road;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

public enum RoadSurface {
    PAVED,
    UNPAVED;

    public static @Nullable RoadSurface fromName(@Nullable String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
