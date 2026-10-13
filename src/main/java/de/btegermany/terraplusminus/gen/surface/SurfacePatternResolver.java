package de.btegermany.terraplusminus.gen.surface;

import de.btegermany.terraplusminus.gen.landcover.LandCoverType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

//TODO: move to T--
public class SurfacePatternResolver {

    private final List<SurfacePattern> patterns;

    public SurfacePatternResolver(List<SurfacePattern> patterns) {
        List<SurfacePattern> sorted = new ArrayList<>(patterns);
        sorted.sort(Comparator.naturalOrder()); // stable sort: equal priorities keep their configuration order
        this.patterns = List.copyOf(sorted);
    }

    public Optional<SurfacePattern> resolve(int irlHeight, double slopeAngle, LandCoverType landCover) {
        for (SurfacePattern pattern : this.patterns) {
            if (pattern.matches(irlHeight, slopeAngle, landCover)) {
                return Optional.of(pattern);
            }
        }
        return Optional.empty();
    }

    public boolean isEmpty() {
        return this.patterns.isEmpty();
    }
}
