package de.btegermany.terraplusminus.gen.road;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RoadMaterialResolver {
    private final List<RoadMaterialRule> rules;

    public RoadMaterialResolver(@NonNull List<RoadMaterialRule> rules) {
        List<RoadMaterialRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingDouble(RoadMaterialRule::getPriority).reversed()); // stable
        this.rules = List.copyOf(sorted);
    }

    public @Nullable RoadMaterialRule resolve(
            @NonNull String datasetId,
            @NonNull RoadKind kind,
            @Nullable RoadClass roadClass,
            @Nullable RoadSurface surface
    ) {
        for (RoadMaterialRule rule : this.rules) {
            if (rule.matches(datasetId, kind, roadClass, surface)) return rule;
        }
        return null;
    }

    public boolean isEmpty() {
        return this.rules.isEmpty();
    }
}
