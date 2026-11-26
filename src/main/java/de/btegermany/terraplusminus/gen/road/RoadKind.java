package de.btegermany.terraplusminus.gen.road;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

public enum RoadKind {
    PATH(0),
    PAVED_AREA(1),
    PARKING(2),
    ROADWAY(3),
    SIDEWALK(4),
    TRAFFIC_ISLAND(5);

    private final int rank;

    RoadKind(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return this.rank;
    }

    public static @Nullable RoadKind fromName(@Nullable String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
