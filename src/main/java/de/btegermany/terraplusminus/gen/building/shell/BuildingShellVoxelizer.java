package de.btegermany.terraplusminus.gen.building.shell;

import java.util.HashSet;
import java.util.Set;
import lombok.NonNull;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;

/**
 * Voxelizes a BuildingShell mesh into a hollow 1-block-thick shell using
 * the generalized winding number (sum of signed solid angles).
 */
public final class BuildingShellVoxelizer {

    // Winding number threshold: sum >= 2π means the point is inside the mesh.
    private static final double TWO_PI = 2.0d * Math.PI;

    // Quantization grid for vertex welding after projection (in Minecraft blocks).
    private static final double VERTEX_WELD_GRID = 1.0e-3d;

    /**
     * Voxelizes a building shell into Minecraft block positions.
     *
     * @param shell      the building shell in WGS84
     * @param projection WGS84 → Minecraft x/z projection
     * @param yOffset    added to elevations
     * @return block positions forming the hollow shell
     */
    public static Set<BlockPos> voxelize(@NonNull BuildingShell shell, @NonNull GeographicProjection projection, int yOffset) {
        // Project vertices and weld duplicates to close seams from the nonlinear projection.
        java.util.Map<LongPoint, Vec3> weldMap = new java.util.HashMap<>();
        Triangle[] mcTriangles = new Triangle[shell.triangles().size()];
        int triIdx = 0;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (BuildingShell.Triangle tri : shell.triangles()) {
            Vec3[] verts = new Vec3[3];
            for (int i = 0; i < 3; i++) {
                BuildingShell.Vertex v = tri.vertices()[i];
                try {
                    double[] mc = projection.fromGeo(v.lon(), v.lat());
                    Vec3 welded = weldVertex(weldMap, mc[0], v.elevation() + yOffset, mc[1]);
                    verts[i] = welded;
                    minX = Math.min(minX, welded.x);
                    minY = Math.min(minY, welded.y);
                    minZ = Math.min(minZ, welded.z);
                    maxX = Math.max(maxX, welded.x);
                    maxY = Math.max(maxY, welded.y);
                    maxZ = Math.max(maxZ, welded.z);
                } catch (OutOfProjectionBoundsException e) {
                    verts[i] = null;
                }
            }
            if (verts[0] == null || verts[1] == null || verts[2] == null) continue;
            if (verts[0].equals(verts[1]) || verts[1].equals(verts[2]) || verts[0].equals(verts[2])) continue;
            mcTriangles[triIdx++] = new Triangle(verts[0], verts[1], verts[2]);
        }

        if (triIdx == 0) return Set.of();

        Triangle[] validTriangles = new Triangle[triIdx];
        System.arraycopy(mcTriangles, 0, validTriangles, 0, triIdx);

        int startX = (int) Math.floor(minX) - 1;
        int startY = (int) Math.floor(minY) - 1;
        int startZ = (int) Math.floor(minZ) - 1;
        int endX = (int) Math.ceil(maxX) + 1;
        int endY = (int) Math.ceil(maxY) + 1;
        int endZ = (int) Math.ceil(maxZ) + 1;

        // Phase 1: classify inside/outside per block via winding number.
        boolean[][][] inside = new boolean[endX - startX + 1][endY - startY + 1][endZ - startZ + 1];

        for (int bx = startX; bx <= endX; bx++) {
            for (int by = startY; by <= endY; by++) {
                for (int bz = startZ; bz <= endZ; bz++) {
                    inside[bx - startX][by - startY][bz - startZ] =
                        computeWindingNumber(bx + 0.5d, by + 0.5d, bz + 0.5d, validTriangles) >= TWO_PI;
                }
            }
        }

        // Phase 2: extract surface blocks (inside with at least one outside neighbor).
        Set<BlockPos> shellBlocks = new HashSet<>();
        int dx = endX - startX;
        int dy = endY - startY;
        int dz = endZ - startZ;

        for (int ix = 0; ix <= dx; ix++) {
            for (int iy = 0; iy <= dy; iy++) {
                for (int iz = 0; iz <= dz; iz++) {
                    if (!inside[ix][iy][iz]) continue;

                    boolean isSurface =
                        (ix == 0 || !inside[ix - 1][iy][iz]) ||
                        (ix == dx || !inside[ix + 1][iy][iz]) ||
                        (iy == 0 || !inside[ix][iy - 1][iz]) ||
                        (iy == dy || !inside[ix][iy + 1][iz]) ||
                        (iz == 0 || !inside[ix][iy][iz - 1]) ||
                        (iz == dz || !inside[ix][iy][iz + 1]);

                    if (!isSurface) continue;
                    shellBlocks.add(new BlockPos(startX + ix, startY + iy, startZ + iz));
                }
            }
        }

        return shellBlocks;
    }

    /**
     * Generalized winding number via the Van Oosterom–Strackee solid angle formula.
     * Returns 4π for points inside a closed mesh, 0 outside, with a continuous
     * threshold at 2π for imperfect meshes.
     */
    private static double computeWindingNumber(double ox, double oy, double oz, Triangle[] triangles) {
        double sum = 0.0d;
        for (Triangle tri : triangles) {
            double ax = tri.v0.x - ox, ay = tri.v0.y - oy, az = tri.v0.z - oz;
            double bx = tri.v1.x - ox, by = tri.v1.y - oy, bz = tri.v1.z - oz;
            double cx = tri.v2.x - ox, cy = tri.v2.y - oy, cz = tri.v2.z - oz;

            double lenA = Math.sqrt(ax * ax + ay * ay + az * az);
            double lenB = Math.sqrt(bx * bx + by * by + bz * bz);
            double lenC = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (lenA < 1e-12d || lenB < 1e-12d || lenC < 1e-12d) continue;

            // numerator = A*(B*C)
            double numerator = ax * (by * cz - bz * cy)
                             + ay * (bz * cx - bx * cz)
                             + az * (bx * cy - by * cx);

            // denominator = |A|*|B|*|C| + (A*B)*|C| + (B*C)*|A| + (C*A)*|B|
            double denominator = lenA * lenB * lenC
                + (ax * bx + ay * by + az * bz) * lenC
                + (bx * cx + by * cy + bz * cz) * lenA
                + (cx * ax + cy * ay + cz * az) * lenB;

            if (Math.abs(denominator) < 1e-18d) continue;

            sum += 2.0d * Math.atan2(numerator, denominator);
        }
        return Math.abs(sum);
    }

    /** Snap a vertex to the quantization grid and deduplicate. */
    private static Vec3 weldVertex(java.util.Map<LongPoint, Vec3> weldMap, double x, double y, double z) {
        long gx = Math.round(x / VERTEX_WELD_GRID);
        long gy = Math.round(y / VERTEX_WELD_GRID);
        long gz = Math.round(z / VERTEX_WELD_GRID);
        LongPoint key = new LongPoint(gx, gy, gz);
        return weldMap.computeIfAbsent(key,
            k -> new Vec3(gx * VERTEX_WELD_GRID, gy * VERTEX_WELD_GRID, gz * VERTEX_WELD_GRID));
    }

    private record Vec3(double x, double y, double z) {}

    private record Triangle(Vec3 v0, Vec3 v1, Vec3 v2) {}

    private record LongPoint(long x, long y, long z) {}

    public record BlockPos(int x, int y, int z) {
        @Override
        public int hashCode() {
            return 31 * (31 * x + y) + z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BlockPos(int x1, int y1, int z1))) return false;
            return this.x == x1 && this.y == y1 && this.z == z1;
        }
    }
}