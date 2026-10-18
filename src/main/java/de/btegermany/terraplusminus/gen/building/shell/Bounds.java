package de.btegermany.terraplusminus.gen.building.shell;

import java.util.List;

/**
 * Bounding box for a building shell in WGS84.
 */
public record Bounds(double minLon, double minLat, double minZ, double maxLon, double maxLat, double maxZ) {
    public boolean intersects2D(double otherMinLon, double otherMinLat, double otherMaxLon, double otherMaxLat) {
        return this.minLon <= otherMaxLon && this.maxLon >= otherMinLon
                && this.minLat <= otherMaxLat && this.maxLat >= otherMinLat;
    }

    public double distanceTo(double lon, double lat) {
        double closestLon = Math.clamp(lon, this.minLon, this.maxLon);
        double closestLat = Math.clamp(lat, this.minLat, this.maxLat);
        return GeoMath.haversine(lat, lon, closestLat, closestLon);
    }

    public static Bounds fromTriangles(List<BuildingShell.Triangle> triangles) {
        double minLon = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (BuildingShell.Triangle tri : triangles) {
            for (BuildingShell.Vertex v : tri.vertices()) {
                minLon = Math.min(minLon, v.lon());
                minLat = Math.min(minLat, v.lat());
                minZ = Math.min(minZ, v.elevation());
                maxLon = Math.max(maxLon, v.lon());
                maxLat = Math.max(maxLat, v.lat());
                maxZ = Math.max(maxZ, v.elevation());
            }
        }

        return !Double.isInfinite(minLon)
                ? new Bounds(minLon, minLat, minZ, maxLon, maxLat, maxZ)
                : new Bounds(0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
    }

    }
