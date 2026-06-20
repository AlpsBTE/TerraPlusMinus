package de.btegermany.terraplusminus.gen.swiss.buildings3d;

import java.util.List;

/**
 * Bounding box for a SwissBuildings3D shell in WGS84.
 */
public record Bounds(double minLon, double minLat, double minZ, double maxLon, double maxLat, double maxZ) {
    public boolean intersects2D(double otherMinLon, double otherMinLat, double otherMaxLon, double otherMaxLat) {
        return this.minLon <= otherMaxLon && this.maxLon >= otherMinLon
                && this.minLat <= otherMaxLat && this.maxLat >= otherMinLat;
    }

    public double distanceTo(double lon, double lat) {
        double closestLon = clamp(lon, this.minLon, this.maxLon);
        double closestLat = clamp(lat, this.minLat, this.maxLat);
        return haversine(lat, lon, closestLat, closestLon);
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

        if (Double.isInfinite(minLon)) {
            return new Bounds(0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
        }
        return new Bounds(minLon, minLat, minZ, maxLon, maxLat, maxZ);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadius = 6_371_000.0d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2.0d) * Math.sin(dLat / 2.0d)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2.0d) * Math.sin(dLon / 2.0d);
        double c = 2.0d * Math.atan2(Math.sqrt(a), Math.sqrt(1.0d - a));
        return earthRadius * c;
    }
}
