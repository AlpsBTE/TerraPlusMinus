package de.btegermany.terraplusminus.gen.surface;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.btegermany.terraplusminus.gen.landcover.LandCoverType;
import lombok.Getter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

//TODO: move to T--
@Getter
public class SurfacePattern implements Comparable<SurfacePattern> {
    public record Stratum(SurfacePatternLayer layer, long seed) {
    }

    private final String name;
    private final double priority;
    private final Integer minHeight;
    private final Integer maxHeight;
    private final double minSlope;
    private final Double maxSlope;

    private final Set<LandCoverType> landCoverWhitelist;
    private final Set<LandCoverType> landCoverBlacklist;
    private final int landCoverEdgeFalloff;
    private final long landCoverEdgeFalloffSeed;

    private static final long LANDCOVER_EDGE_FALLOFF_SEED = 0x7A4D789C3E2B1D55L;

    private final List<Stratum> strata;
    private final int[] strataStartDepths;
    private final int strataDepth;

    @JsonCreator
    public SurfacePattern(
            @JsonProperty("name") String name,
            @JsonProperty("priority") Double priority,
            @JsonProperty("min_height") Integer minHeight,
            @JsonProperty("max_height") Integer maxHeight,
            @JsonProperty("min_slope") Double minSlope,
            @JsonProperty("max_slope") Double maxSlope,
            @JsonProperty("land_cover_whitelist") List<String> landCoverWhitelist,
            @JsonProperty("land_cover_blacklist") List<String> landCoverBlacklist,
            @JsonProperty("land_cover_edge_falloff") Integer landCoverEdgeFalloff,
            @JsonProperty(value = "strata", required = true) List<SurfacePatternLayer> strata) {
        this.name = name;
        this.priority = priority == null ? 0.0d : priority;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.minSlope = minSlope == null ? 0.0d : minSlope;
        this.maxSlope = maxSlope;
        this.landCoverWhitelist = parseLandCoverSet(landCoverWhitelist);
        this.landCoverBlacklist = parseLandCoverSet(landCoverBlacklist);
        this.landCoverEdgeFalloff = landCoverEdgeFalloff == null ? 0 : landCoverEdgeFalloff;
        if (this.landCoverEdgeFalloff < 0) {
            throw new IllegalArgumentException("pattern '" + this.displayName()
                    + "': land_cover_edge_falloff must be non-negative, got " + this.landCoverEdgeFalloff);
        }
        this.landCoverEdgeFalloffSeed = this.landCoverEdgeFalloff > 0 && this.landCoverWhitelist.size() == 1
                ? SpeckleHash.hash(LANDCOVER_EDGE_FALLOFF_SEED, this.name == null ? 0 : this.name.hashCode(), 0, 0)
                : 0L;
        if (strata == null || strata.isEmpty()) {
            throw new IllegalArgumentException("pattern '" + this.displayName() + "' must define a non-empty 'strata' list");
        }
        for (int i = 0; i < strata.size() - 1; i++) {
            if (strata.get(i).isInfinite()) {
                throw new IllegalArgumentException("pattern '" + this.displayName() + "': only the last stratum may omit its depth to be unbounded");
            }
        }

        long seedBase = SpeckleHash.hash(0x517CC1B727220A95L, this.name == null ? 0 : this.name.hashCode(), strata.size(), 0);
        List<Stratum> seededStrata = new ArrayList<>(strata.size());
        int[] startDepths = new int[strata.size()];
        int depth = 0;
        for (int i = 0; i < strata.size(); i++) {
            SurfacePatternLayer layer = strata.get(i);
            startDepths[i] = depth;
            seededStrata.add(new Stratum(layer, SpeckleHash.hash(seedBase, i, 0x5EED, 0x5EED)));
            if (!layer.isInfinite()) {
                depth += layer.getDepth();
            }
        }
        this.strata = List.copyOf(seededStrata);
        this.strataStartDepths = startDepths;
        this.strataDepth = depth;
    }

    public boolean matches(int irlHeight, double slopeAngle, LandCoverType landCover) {
        if (this.minHeight != null && irlHeight < this.minHeight) return false;
        if (this.maxHeight != null && irlHeight > this.maxHeight) return false;
        if (slopeAngle < this.minSlope) return false;
        if (this.maxSlope != null && slopeAngle > this.maxSlope) return false;
        return this.matchesLandCover(landCover);
    }

    /**
     * Same as {@link #matches(int, double, LandCoverType)}, but applies the configured
     * landcover edge falloff when the block is close to a landcover boundary.
     */
    public boolean matches(int irlHeight, double slopeAngle, LandCoverType landCover,
                           int edgeDistance, int blockX, int blockY, int blockZ) {
        if (!this.matches(irlHeight, slopeAngle, landCover)) {
            return false;
        }
        return this.testEdgeFalloff(edgeDistance, blockX, blockY, blockZ);
    }

    private boolean matchesLandCover(LandCoverType landCover) {
        if (landCover == null) {
            return this.landCoverWhitelist.isEmpty() && this.landCoverBlacklist.isEmpty();
        }
        if (!this.landCoverWhitelist.isEmpty() && !this.landCoverWhitelist.contains(landCover)) {
            return false;
        }
        return !this.landCoverBlacklist.contains(landCover);
    }

    /**
     * Edge falloff is only supported for single-type whitelists: the distance is defined as
     * the distance to the nearest column whose type differs from that single whitelisted type.
     * For multi-type whitelists or when no falloff is configured the pattern always passes.
     */
    private boolean testEdgeFalloff(int edgeDistance, int blockX, int blockY, int blockZ) {
        if (this.landCoverEdgeFalloff <= 0 || this.landCoverWhitelist.size() != 1) {
            return true;
        }
        if (edgeDistance >= this.landCoverEdgeFalloff) {
            return true;
        }
        double probability = (double) edgeDistance / this.landCoverEdgeFalloff;
        double randomValue = SpeckleHash.value(this.landCoverEdgeFalloffSeed, blockX, blockY, blockZ);
        return randomValue < probability;
    }

    private static Set<LandCoverType> parseLandCoverSet(List<String> names) {
        if (names == null || names.isEmpty()) {
            return EnumSet.noneOf(LandCoverType.class);
        }
        Set<LandCoverType> set = EnumSet.noneOf(LandCoverType.class);
        for (String name : names) {
            LandCoverType type = LandCoverType.fromName(name);
            if (type != LandCoverType.UNKNOWN) {
                set.add(type);
            }
        }
        return set;
    }

    public Stratum stratumAt(int depth) {
        Stratum stratum = this.finiteStratumAt(depth);
        if (stratum != null) return stratum;

        Stratum last = this.strata.getLast();
        return last.layer().isInfinite() ? last : null;
    }

    public Stratum finiteStratumAt(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be at least 0, got " + depth);
        }
        if (depth >= this.strataDepth) return null;
        for (int i = this.strata.size() - 1; i >= 0; i--) {
            if (depth >= this.strataStartDepths[i]) {
                return this.strata.get(i);
            }
        }
        throw new IllegalStateException("no stratum contains depth " + depth);
    }

    @Override
    public int compareTo(SurfacePattern other) {
        return -Double.compare(this.priority, other.priority);
    }

    String displayName() {
        return this.name == null ? "<unnamed>" : this.name;
    }

    @Override
    public String toString() {
        return "SurfacePattern{name='" + this.displayName() + "', priority=" + this.priority
                + ", height=[" + (this.minHeight == null ? "-∞" : this.minHeight) + ", " + (this.maxHeight == null ? "∞" : this.maxHeight) + "]"
                + ", slope=[" + this.minSlope + "°, " + (this.maxSlope == null ? "90" : this.maxSlope) + "°]"
                + ", land_cover_edge_falloff=" + this.landCoverEdgeFalloff
                + ", strata=" + this.strata.size() + " layer(s), " + this.strataDepth + " block(s) deep}";
    }
}
