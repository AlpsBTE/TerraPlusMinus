package de.btegermany.terraplusminus.gen.surface;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

//TODO: move to T--
@Getter
public class SurfacePatternsConfig {
    public static final int DEFAULT_SLOPE_RADIUS = 4;
    public static final int MAX_SLOPE_RADIUS = 16;

    private final boolean enabled;
    private final int slopeRadius;
    private final List<SurfacePattern> patterns;

    @JsonCreator
    public SurfacePatternsConfig(
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("slope_radius") Integer slopeRadius,
            @JsonProperty("patterns") List<SurfacePattern> patterns) {
        this.enabled = enabled != null && enabled;
        if (slopeRadius != null && (slopeRadius < 1 || slopeRadius > MAX_SLOPE_RADIUS)) {
            throw new IllegalArgumentException("slope_radius must be between 1 and " + MAX_SLOPE_RADIUS + ", got " + slopeRadius);
        }
        this.slopeRadius = slopeRadius == null ? DEFAULT_SLOPE_RADIUS : slopeRadius;
        this.patterns = patterns == null ? List.of() : List.copyOf(patterns);
    }
}
