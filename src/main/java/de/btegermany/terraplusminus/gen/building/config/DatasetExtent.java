package de.btegermany.terraplusminus.gen.building.config;

import static de.btegermany.terraplusminus.gen.building.config.JsonFields.requiredDouble;

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
}
