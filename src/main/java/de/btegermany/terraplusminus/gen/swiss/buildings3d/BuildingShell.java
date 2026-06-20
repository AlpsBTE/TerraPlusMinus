package de.btegermany.terraplusminus.gen.swiss.buildings3d;

import lombok.NonNull;

import java.util.List;

/**
 * Represents a single building shell from the SwissBuildings3D dataset.
 * Triangles are stored in WGS84 (lon, lat) with orthometric elevation (z) in meters.
 */
public record BuildingShell(String id, String egid, String uuid, List<Triangle> triangles, Bounds bounds) {
    public BuildingShell(@NonNull String id, String egid, String uuid, @NonNull List<Triangle> triangles) {
        this(id, egid, uuid, triangles, Bounds.fromTriangles(triangles));
    }

    public BuildingShell {
        if (bounds == null) {
            bounds = Bounds.fromTriangles(triangles);
        }
    }

    /**
     * Computes the centroid of this building in WGS84.
     * Uses the average of all triangle vertices.
     */
    public double[] centroid() {
        double sumLon = 0.0d;
        double sumLat = 0.0d;
        double sumZ = 0.0d;
        int count = 0;

        for (Triangle tri : this.triangles) {
            for (Vertex v : tri.vertices) {
                sumLon += v.lon;
                sumLat += v.lat;
                sumZ += v.elevation;
                count++;
            }
        }

        if (count == 0) return new double[]{0.0d, 0.0d, 0.0d};
        return new double[]{sumLon / count, sumLat / count, sumZ / count};
    }

    public record Triangle(Vertex[] vertices) {
        public Triangle {
            if (vertices.length != 3) {
                throw new IllegalArgumentException("Triangle must have exactly 3 vertices");
            }
        }
    }

    public record Vertex(double lon, double lat, double elevation) {
    }
}
