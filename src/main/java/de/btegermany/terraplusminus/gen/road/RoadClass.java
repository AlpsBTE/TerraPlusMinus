package de.btegermany.terraplusminus.gen.road;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

public enum RoadClass {
    MOTORWAY,
    MOTORWAY_LINK,
    TRUNK,
    TRUNK_LINK,
    PRIMARY,
    PRIMARY_LINK,
    SECONDARY,
    SECONDARY_LINK,
    TERTIARY,
    TERTIARY_LINK,
    UNCLASSIFIED,
    RESIDENTIAL,
    LIVING_STREET,
    SERVICE,
    PEDESTRIAN,
    TRACK,
    PATH,
    FOOTWAY,
    CYCLEWAY,
    BRIDLEWAY,
    STEPS;

    public static @Nullable RoadClass fromName(@Nullable String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
