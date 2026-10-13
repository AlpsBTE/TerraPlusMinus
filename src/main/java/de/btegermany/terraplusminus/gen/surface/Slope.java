package de.btegermany.terraplusminus.gen.surface;

// TODO: MOVE TO T--
/**
 * Terrain slope computations over an integer block heightfield.
 */
public final class Slope {
    private Slope() {
    }

    @FunctionalInterface
    public interface HeightField {
        int height(int blockX, int blockZ);
    }

    /**
     * Computes the terrain angle at the given column.
     * The gradient is measured over a horizontal span of {@code 2 * radius} blocks in each axis.
     * The larger the radius, the finer the angle thresholds.
     */
    public static double angleDegrees(HeightField field, int blockX, int blockZ, int radius) {
        if (radius < 1) throw new IllegalArgumentException("slope sampling radius must be at least 1, got " + radius);

        double gradientX = (field.height(blockX + radius, blockZ) - field.height(blockX - radius, blockZ)) / (double) (2 * radius);
        double gradientZ = (field.height(blockX, blockZ + radius) - field.height(blockX, blockZ - radius)) / (double) (2 * radius);
        double slope = Math.sqrt(gradientX * gradientX + gradientZ * gradientZ);
        return Math.toDegrees(Math.atan(slope));
    }

    /**
     * Computes the terrain angle in the given cardinal direction
     */
    public static double angleDegreesToward(HeightField field, int blockX, int blockZ, int dx, int dz, int radius) {
        if (radius < 1) {
            throw new IllegalArgumentException("slope sampling radius must be at least 1, got " + radius);
        }
        if (Math.abs(dx) + Math.abs(dz) != 1) {
            throw new IllegalArgumentException("direction must be a cardinal unit vector, got [" + dx + ", " + dz + "]");
        }
        double gradient = (field.height(blockX, blockZ) - field.height(blockX + dx * radius, blockZ + dz * radius)) / (double) radius;
        return Math.toDegrees(Math.atan(Math.max(gradient, 0.0)));
    }
}
