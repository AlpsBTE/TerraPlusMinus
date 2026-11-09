package de.btegermany.terraplusminus.gen.tree;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * One tree from customTrees.json, with its blocks relative to the trunk base.
 *
 * @param name   {@code type/size/name} as in the file
 * @param blocks the blocks, never empty
 * @param minY   lowest block, relative to the trunk base
 * @param maxY   highest block, relative to the trunk base
 */
public record TreeTemplate(@NonNull String name, @NonNull List<TreeBlock> blocks, int minY, int maxY) {

    public static @NonNull TreeTemplate of(@NonNull String name, @NonNull List<TreeBlock> blocks) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (TreeBlock block : blocks) {
            minY = Math.min(minY, block.getY());
            maxY = Math.max(maxY, block.getY());
        }
        return new TreeTemplate(name, List.copyOf(blocks), minY, maxY);
    }

    /**
     * Height in blocks, from the lowest to the highest block.
     */
    public int getHeight() {
        return this.maxY - this.minY + 1;
    }
}
