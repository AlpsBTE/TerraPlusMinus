package de.btegermany.terraplusminus.gen.building.config;

import com.fasterxml.jackson.databind.JsonNode;
import net.buildtheearth.terraminusminus.util.bvh.Bounds2d;

public record DatasetExtent(double minLon, double minLat, double maxLon, double maxLat) {
    public static DatasetExtent fromJson(JsonNode node) {
        double minLon = requiredDouble(node, "minX");
        double maxLon = requiredDouble(node, "maxX");
        double minLat = requiredDouble(node, "minZ");
        double maxLat = requiredDouble(node, "maxZ");
        return new DatasetExtent(minLon, minLat, maxLon, maxLat);
    }

    public Bounds2d toBounds2d() {
        return Bounds2d.of(this.minLon, this.maxLon, this.minLat, this.maxLat);
    }

    public boolean intersects(Bounds2d bounds) {
        return this.minLon <= bounds.maxX() && this.maxLon >= bounds.minX()
                && this.minLat <= bounds.maxZ() && this.maxLat >= bounds.minZ();
    }

    private static double requiredDouble(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Missing numeric bounds field: " + field);
        }
        return value.asDouble();
    }
}
