package de.btegermany.terraplusminus.gen.swiss.buildings3d;

import lombok.NonNull;

import java.util.List;

/**
 * Represents a single building shell from the SwissBuildings3D dataset.
 * Triangles are stored in WGS84 (lon, lat) with orthometric elevation (z) in meters.
 */
public record BuildingShell(String id, String egid, String uuid, List<Triangle> triangles) {
    public BuildingShell(@NonNull String id, String egid, String uuid, @NonNull List<Triangle> triangles) {
        this.id = id;
        this.egid = egid;
        this.uuid = uuid;
        this.triangles = triangles;
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

    /**
     * Computes the bounding box in WGS84: {minLon, minLat, minZ, maxLon, maxLat, maxZ}.
     */
    public double[] bounds() {
        double minLon = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (Triangle tri : this.triangles) {
            for (Vertex v : tri.vertices) {
                minLon = Math.min(minLon, v.lon);
                minLat = Math.min(minLat, v.lat);
                minZ = Math.min(minZ, v.elevation);
                maxLon = Math.max(maxLon, v.lon);
                maxLat = Math.max(maxLat, v.lat);
                maxZ = Math.max(maxZ, v.elevation);
            }
        }

        return new double[]{minLon, minLat, minZ, maxLon, maxLat, maxZ};
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
