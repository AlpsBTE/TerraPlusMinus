package de.btegermany.terraplusminus.gen.swiss.buildings3d;

import java.util.HashSet;
import java.util.Set;
import lombok.NonNull;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;

/**
 * Voxelizes a closed BuildingShell TIN mesh into a hollow 1-block-thick shell
 * using ray-casting (Möller–Trumbore intersection tests).
 */
public final class BuildingShellVoxelizer {

    // Slightly off-axis ray direction to avoid edge/vertex degeneracies
    private static final double RAY_DX = 0.123456789d;
    private static final double RAY_DY = 0.987654321d;
    private static final double RAY_DZ = 0.456789123d;

    private static final double EPSILON = 1e-6d;

    /**
     * Voxelizes a building shell into Minecraft block positions.
     *
     * @param shell      the building shell in WGS84
     * @param projection the TerraMinusMinus projection to convert WGS84 → Minecraft x/z
     * @param yOffset    the world's yOffset to add to elevations
     * @return a set of block positions forming the hollow 1-block-thick shell
     */
    public static Set<BlockPos> voxelize(@NonNull BuildingShell shell, @NonNull GeographicProjection projection, int yOffset) {
        // Convert all vertices to Minecraft coordinates
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
                    double x = mc[0];
                    double z = mc[1];
                    double y = v.elevation() + yOffset;
                    verts[i] = new Vec3(x, y, z);
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                } catch (OutOfProjectionBoundsException e) {
                    // Skip unprojectable vertices
                    verts[i] = null;
                }
            }
            if (verts[0] == null || verts[1] == null || verts[2] == null) continue;
            mcTriangles[triIdx++] = new Triangle(verts[0], verts[1], verts[2]);
        }

        if (triIdx == 0) return Set.of();

        // Trim triangle array to actual size
        Triangle[] validTriangles = new Triangle[triIdx];
        System.arraycopy(mcTriangles, 0, validTriangles, 0, triIdx);

        // Compute integer bounding box with padding
        int startX = (int) Math.floor(minX) - 1;
        int startY = (int) Math.floor(minY) - 1;
        int startZ = (int) Math.floor(minZ) - 1;
        int endX = (int) Math.ceil(maxX) + 1;
        int endY = (int) Math.ceil(maxY) + 1;
        int endZ = (int) Math.ceil(maxZ) + 1;

        // Phase 1: Determine inside/outside for each block
        boolean[][][] inside = new boolean[endX - startX + 1][endY - startY + 1][endZ - startZ + 1];

        for (int bx = startX; bx <= endX; bx++) {
            for (int by = startY; by <= endY; by++) {
                for (int bz = startZ; bz <= endZ; bz++) {
                    // Ray origin = block center
                    double ox = bx + 0.5d;
                    double oy = by + 0.5d;
                    double oz = bz + 0.5d;
                    int intersections = countRayIntersections(
                        ox, oy, oz,
                        RAY_DX, RAY_DY, RAY_DZ,
                        validTriangles
                    );
                    inside[bx - startX][by - startY][bz - startZ] = (intersections % 2) == 1;
                }
            }
        }

        // Phase 2: Extract hollow shell (inside with at least one outside neighbor)
        Set<BlockPos> shellBlocks = new HashSet<>();
        int dx = endX - startX;
        int dy = endY - startY;
        int dz = endZ - startZ;

        for (int ix = 0; ix <= dx; ix++) {
            for (int iy = 0; iy <= dy; iy++) {
                for (int iz = 0; iz <= dz; iz++) {
                    if (!inside[ix][iy][iz]) continue;

                    // Check 6-neighbourhood for an outside block
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
     * Counts how many times a ray from (ox,oy,oz) in direction (dx,dy,dz) intersects the triangle mesh.
     */
    private static int countRayIntersections(
        double ox, double oy, double oz,
        double dx, double dy, double dz,
        Triangle[] triangles
    ) {
        int count = 0;
        for (Triangle tri : triangles) {
            if (!rayIntersectsTriangle(ox, oy, oz, dx, dy, dz, tri)) continue;
            count++;
        }
        return count;
    }

    /**
     * Möller–Trumbore ray-triangle intersection test.
     * Returns true if the ray from origin in direction dir intersects the triangle.
     */
    private static boolean rayIntersectsTriangle(
        double ox, double oy, double oz,
        double dx, double dy, double dz,
        Triangle tri
    ) {
        Vec3 edge1 = tri.v1.subtract(tri.v0);
        Vec3 edge2 = tri.v2.subtract(tri.v0);
        Vec3 h = cross(new Vec3(dx, dy, dz), edge2);
        double a = dot(edge1, h);

        if (Math.abs(a) < EPSILON) return false; // Ray is parallel to triangle

        double f = 1.0d / a;
        Vec3 s = new Vec3(ox - tri.v0.x, oy - tri.v0.y, oz - tri.v0.z);
        double u = f * dot(s, h);

        if (u < 0.0d || u > 1.0d) return false;

        Vec3 q = cross(s, edge1);
        double v = f * dot(new Vec3(dx, dy, dz), q);

        if (v < 0.0d || u + v > 1.0d) return false;

        double t = f * dot(edge2, q);
        return t > EPSILON;
    }

    private static double dot(Vec3 a, Vec3 b) {
        return a.x * b.x + a.y * b.y + a.z * b.z;
    }

    private static Vec3 cross(Vec3 a, Vec3 b) {
        return new Vec3(
            a.y * b.z - a.z * b.y,
            a.z * b.x - a.x * b.z,
            a.x * b.y - a.y * b.x
        );
    }

    private record Vec3(double x, double y, double z) {
        Vec3 subtract(Vec3 other) {
            return new Vec3(
                this.x - other.x,
                this.y - other.y,
                this.z - other.z
            );
        }
    }

    private record Triangle(Vec3 v0, Vec3 v1, Vec3 v2) {}

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
