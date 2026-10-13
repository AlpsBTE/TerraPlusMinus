package de.btegermany.terraplusminus.gen.surface;

//TODO: move to T--

/**
 * Sampler for random block perlin noise patches. Just like WorldEdit :)
 */
public final class NoiseSampler {
    /**
     * Seed difference between the two octaves, so the octaves are uncorrelated.
     */
    private static final long SECOND_OCTAVE_SEED_KNOB = 0x6A09E667F3BCC909L;

    private NoiseSampler() {
    }

    public static double sample2d(long seed, double scale, int x, int z) {
        return (value2d(seed, scale, x, z) * 2.0
                + value2d(seed ^ SECOND_OCTAVE_SEED_KNOB, scale * 0.5, x, z)) / 3.0;
    }

    public static double sample3d(long seed, double scale, int x, int y, int z) {
        return (value3d(seed, scale, x, y, z) * 2.0
                + value3d(seed ^ SECOND_OCTAVE_SEED_KNOB, scale * 0.5, x, y, z)) / 3.0;
    }

    private static double value2d(long seed, double scale, int x, int z) {
        double fx = x / scale;
        double fz = z / scale;
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = perlin(fx - x0);
        double tz = perlin(fz - z0);
        double v00 = SpeckleHash.value(seed, x0, 0, z0);
        double v10 = SpeckleHash.value(seed, x0 + 1, 0, z0);
        double v01 = SpeckleHash.value(seed, x0, 0, z0 + 1);
        double v11 = SpeckleHash.value(seed, x0 + 1, 0, z0 + 1);
        return lerp(lerp(v00, v10, tx), lerp(v01, v11, tx), tz);
    }

    private static double value3d(long seed, double scale, int x, int y, int z) {
        double fx = x / scale;
        double fy = y / scale;
        double fz = z / scale;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        int z0 = (int) Math.floor(fz);
        double tx = perlin(fx - x0);
        double ty = perlin(fy - y0);
        double tz = perlin(fz - z0);
        double v000 = SpeckleHash.value(seed, x0, y0, z0);
        double v100 = SpeckleHash.value(seed, x0 + 1, y0, z0);
        double v010 = SpeckleHash.value(seed, x0, y0 + 1, z0);
        double v110 = SpeckleHash.value(seed, x0 + 1, y0 + 1, z0);
        double v001 = SpeckleHash.value(seed, x0, y0, z0 + 1);
        double v101 = SpeckleHash.value(seed, x0 + 1, y0, z0 + 1);
        double v011 = SpeckleHash.value(seed, x0, y0 + 1, z0 + 1);
        double v111 = SpeckleHash.value(seed, x0 + 1, y0 + 1, z0 + 1);
        return lerp(
                lerp(lerp(v000, v100, tx), lerp(v010, v110, tx), ty),
                lerp(lerp(v001, v101, tx), lerp(v011, v111, tx), ty),
                tz
        );
    }

    private static double perlin(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
