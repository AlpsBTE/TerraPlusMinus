package de.btegermany.terraplusminus.gen.surface;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import net.buildtheearth.terraminusminus.substitutes.BlockState;

//TODO: move to T--
@Getter
public class SurfacePatternBlock {

    private final BlockState block;
    private final double weight;

    @JsonCreator
    public SurfacePatternBlock(
            @JsonProperty(value = "block", required = true) String block,
            @JsonProperty("weight") Double weight) {
        this.block = BlockState.parse(block);
        this.weight = weight == null ? 1.0d : weight;
        if (this.weight <= 0.0d || !Double.isFinite(this.weight)) {
            throw new IllegalArgumentException("block mix weight must be a positive finite number, got " + weight
                    + " (for block '" + block + "')");
        }
    }

    @Override
    public String toString() {
        return this.block + " x" + this.weight;
    }
}
