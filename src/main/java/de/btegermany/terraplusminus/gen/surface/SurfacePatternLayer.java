package de.btegermany.terraplusminus.gen.surface;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

//TODO: move to T--
@Getter
public class SurfacePatternLayer {
    public enum Mode {
        RANDOM, // classic speckle random block mix
        NOISE // perlin noise patches
    }

    public static final double DEFAULT_NOISE_SCALE = 64.0d;

    private final List<SurfacePatternBlock> blocks;

    private final Integer depth;
    private final Mode mode;
    private final double noiseScale;
    private final boolean noise3d;
    private final double totalWeight;

    @JsonCreator
    public SurfacePatternLayer(
            @JsonProperty(value = "blocks", required = true) List<SurfacePatternBlock> blocks,
            @JsonProperty("depth") Integer depth,
            @JsonProperty("mode") String mode,
            @JsonProperty("noise_scale") Double noiseScale,
            @JsonProperty("noise_3d") Boolean noise3d) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("a stratum must define a non-empty 'blocks' mix");
        }
        if (depth != null && depth < 1) {
            throw new IllegalArgumentException("stratum depth must be at least 1, or omitted for an unbounded last layer, got " + depth);
        }

        this.blocks = List.copyOf(blocks);
        this.depth = depth;
        this.mode = parseMode(mode);

        this.noiseScale = noiseScale == null ? DEFAULT_NOISE_SCALE : noiseScale;
        this.noise3d = noise3d != null && noise3d;
        if (this.noiseScale <= 0.0d || !Double.isFinite(this.noiseScale)) {
            throw new IllegalArgumentException("noise_scale must be a positive finite number, got " + noiseScale);
        }

        this.totalWeight = this.blocks.stream().mapToDouble(SurfacePatternBlock::getWeight).sum();
    }

    private static Mode parseMode(String mode) {
        if (mode == null || mode.equalsIgnoreCase("random")) return Mode.RANDOM;
        if (mode.equalsIgnoreCase("noise")) return Mode.NOISE;
        throw new IllegalArgumentException("unknown mix mode '" + mode + "', expected 'random' or 'noise'");
    }

    public boolean isInfinite() {
        return this.depth == null;
    }

    public SurfacePatternBlock pick(double mixValue) {
        double position = mixValue * this.totalWeight;
        for (SurfacePatternBlock block : this.blocks) {
            position -= block.getWeight();
            if (position < 0.0d) {
                return block;
            }
        }
        return this.blocks.getLast();
    }

    @Override
    public String toString() {
        return "SurfacePatternLayer{blocks=" + this.blocks.size()
                + ", depth=" + (this.depth == null ? "unbounded" : this.depth)
                + ", mode=" + this.mode.name().toLowerCase()
                + (this.mode == Mode.NOISE ? ", noise_scale=" + this.noiseScale + (this.noise3d ? ", 3d" : "") : "")
                + "}";
    }
}
