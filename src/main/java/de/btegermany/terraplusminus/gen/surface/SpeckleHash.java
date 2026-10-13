package de.btegermany.terraplusminus.gen.surface;

//TODO: move to T--
/**
 * Deterministic per-block values for classic WE style random block speckle mixes
 */
public final class SpeckleHash {
    private SpeckleHash() {
    }

    /**
     * hash position and seed into a uniformly distributed long
     */
    public static long hash(long seed, int x, int y, int z) {
        long h = finish(seed ^ (x * 0x9E3779B97F4A7C15L));
        h = finish(h ^ (y * 0xC2B2AE3D27D4EB4FL));
        h = finish(h ^ (z * 0x165667B19E3779F9L));
        return h;
    }

    public static double value(long seed, int x, int y, int z) {
        return (hash(seed, x, y, z) >>> 11) * 0x1.0p-53;
    }

    /**
     * splitmix64 finalizer
     */
    private static long finish(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
