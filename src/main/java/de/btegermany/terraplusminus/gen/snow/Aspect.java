package de.btegermany.terraplusminus.gen.snow;

import de.btegermany.terraplusminus.gen.surface.Slope;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;

//TODO: MOVE TO T--

/**
 * Computes the real-world aspect (azimuth) and hemisphere of a terrain column.
 *
 * @author Cinnazeyy
 */
public final class Aspect {

    private Aspect() {
    }

    /**
     * @param azimuth            geographic azimuth in degrees or {@link Double#NaN} if terrain is flat
     * @param southernHemisphere true when the column is south of the equator
     * @param valid              false when the projection cannot map the column
     */
    public record Result(double azimuth, boolean southernHemisphere, boolean valid) {
    }

    public static Result compute(GeographicProjection projection, Slope.HeightField heights, int blockX, int blockZ, int radius) {
        try {
            double gradientX = (heights.height(blockX + radius, blockZ) - heights.height(blockX - radius, blockZ)) / (2.0d * radius);
            double gradientZ = (heights.height(blockX, blockZ + radius) - heights.height(blockX, blockZ - radius)) / (2.0d * radius);

            double azimuth;
            if (gradientX == 0.0d && gradientZ == 0.0d) {
                azimuth = Double.NaN;
            } else {
                double mcYaw = Math.toDegrees(Math.atan2(gradientX, -gradientZ));
                azimuth = projection.azimuth(blockX, blockZ, (float) mcYaw);
            }

            double[] geo = projection.toGeo(blockX, blockZ);
            return new Result(azimuth, geo[1] < 0.0d, true);
        } catch (OutOfProjectionBoundsException e) {
            return new Result(Double.NaN, false, false);
        }
    }
}
