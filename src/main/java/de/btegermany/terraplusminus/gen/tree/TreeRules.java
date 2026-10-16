package de.btegermany.terraplusminus.gen.tree;

import org.bukkit.block.Biome;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Ordered tree rules from {@code tree_rules.json5}, deciding which tree type grows at a location.
 * The first rule whose conditions match applies; the last rule has no conditions and applies everywhere else.
 *
 * @see TreeRulesLoader
 */
public final class TreeRules {

    private final List<TreeRule> rules;
    private final double heightTolerancePercent;

    /**
     * @param heightTolerancePercent with tree height data: templates within the percentage of the target height fit
     */
    TreeRules(@NonNull List<TreeRule> rules, double heightTolerancePercent) {
        this.rules = List.copyOf(rules);
        this.heightTolerancePercent = heightTolerancePercent;
    }

    /**
     * @return the first rule matching the given biome
     */
    public @NonNull TreeRule match(@NonNull Biome biome) {
        for (TreeRule rule : this.rules) {
            if (rule.matches(biome)) {
                return rule;
            }
        }
        return this.rules.getLast(); // Not reached, the last rule is a catch-all
    }

    public int size() {
        return this.rules.size();
    }

    public double heightTolerancePercent() {
        return this.heightTolerancePercent;
    }

    /**
     * @param name             optional name, for messages only
     * @param biomes           biomes the rule applies to, or {@code null} if the rule does not depend on the biome
     * @param broadleafPercent chance in percent that a tree is taken from {@code broadleaf} rather than {@code conifer}
     */
    public record TreeRule(
            @Nullable String name,
            @Nullable Set<Biome> biomes,
            @NonNull TypeChoice broadleaf,
            @NonNull TypeChoice conifer,
            double broadleafPercent
    ) {

        boolean matches(@NonNull Biome biome) {
            return this.biomes == null || this.biomes.contains(biome);
        }

        boolean hasConditions() {
            return this.biomes != null;
        }

        /**
         * @param dataBroadleafPercent broadleaf share at the tree from a dataset, or NaN to use {@link #broadleafPercent}
         * @return the chance in percent that {@link #pickType} chooses the broadleaf side
         */
        public double effectiveBroadleafPercent(double dataBroadleafPercent) {
            return Double.isNaN(dataBroadleafPercent)
                    ? this.broadleafPercent
                    : Math.clamp(dataBroadleafPercent, 0, 100);
        }

        public @Nullable String pickType(@NonNull Random random, double dataBroadleafPercent) {
            boolean wantsBroadleaf = random.nextDouble() * 100 < this.effectiveBroadleafPercent(dataBroadleafPercent);
            TypeChoice preferred = wantsBroadleaf ? this.broadleaf : this.conifer;
            TypeChoice other = wantsBroadleaf ? this.conifer : this.broadleaf;
            if (!preferred.isEmpty()) {
                return preferred.pick(random);
            }
            if (!other.isEmpty()) {
                return other.pick(random);
            }
            return null;
        }
    }

    /**
     * Tree types with weights. Types given as a list are weighted by their number of templates,
     * so all templates of the listed types are equally likely.
     */
    public record TypeChoice(@NonNull List<String> types, double @NonNull [] cumulativeWeights) {

        static final TypeChoice NONE = new TypeChoice(List.of(), new double[0]);

        public boolean isEmpty() {
            return this.types.isEmpty();
        }

        @NonNull String pick(@NonNull Random random) {
            int last = this.types.size() - 1;
            if (last == 0) {
                return this.types.getFirst();
            }
            double target = random.nextDouble() * this.cumulativeWeights[last];
            for (int i = 0; i < last; i++) {
                if (target < this.cumulativeWeights[i]) {
                    return this.types.get(i);
                }
            }
            return this.types.get(last);
        }
    }
}
