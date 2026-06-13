package de.btegermany.terraplusminus.gen.swiss.buildings3d;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import lombok.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads preprocessed SwissBuildings3D tiles and queries for the nearest building shell.
 */
public final class SwissBuildings3DDataset {

    private static final Gson GSON = new GsonBuilder().create();
    private final Path tileDirectory;
    private final List<TileInfo> tiles;

    public SwissBuildings3DDataset(@NonNull Path tileDirectory) {
        this.tileDirectory = tileDirectory;
        this.tiles = loadTileIndex();
    }

    private List<TileInfo> loadTileIndex() {
        List<TileInfo> result = new ArrayList<>();
        Path indexFile = this.tileDirectory.resolve("tile_index.json");
        if (!Files.exists(indexFile)) return result;

        try (JsonReader reader = new JsonReader(Files.newBufferedReader(indexFile))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!"tiles".equals(name)) {
                    reader.skipValue();
                    continue;
                }

                reader.beginArray();
                while (reader.hasNext()) {
                    JsonObject tileObj = GSON.fromJson(reader, JsonObject.class);
                    String tileName = tileObj.get("name").getAsString();
                    JsonObject bounds = tileObj.getAsJsonObject("bounds");
                    double minLon = bounds.get("minLon").getAsDouble();
                    double minLat = bounds.get("minLat").getAsDouble();
                    double maxLon = bounds.get("maxLon").getAsDouble();
                    double maxLat = bounds.get("maxLat").getAsDouble();
                    result.add(new TileInfo(tileName, minLon, minLat, maxLon, maxLat));
                }
                reader.endArray();
            }
            reader.endObject();
        } catch (IOException e) {
            // Return empty list on error
        }

        return result;
    }

    /**
     * Finds the nearest building shell to the given WGS84 coordinate within the specified radius (in meters).
     *
     * @param lon    longitude in degrees
     * @param lat    latitude in degrees
     * @param radius maximum search radius in meters
     * @return the nearest BuildingShell, or null if none is within range
     */
    public BuildingShell findNearestBuilding(double lon, double lat, double radius) {
        // Find tiles that intersect a rough bounding box around the query point
        // 1 degree lat ≈ 111km, 1 degree lon varies but at Swiss latitudes ≈ 78km
        double latDelta = radius / 111_000.0d;
        double lonDelta = radius / (111_000.0d * Math.cos(Math.toRadians(lat)));

        List<TileInfo> candidates = new ArrayList<>();
        for (TileInfo tile : this.tiles) {
            if (!tile.intersects(lon - lonDelta, lat - latDelta, lon + lonDelta, lat + latDelta)) continue;
            candidates.add(tile);
        }

        if (candidates.isEmpty()) return null;

        BuildingShell nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;

        for (TileInfo tile : candidates) {
            Path buildingsFile = this.tileDirectory.resolve(tile.name).resolve("buildings.jsonl");
            if (!Files.exists(buildingsFile)) continue;

            try (BufferedReader reader = Files.newBufferedReader(buildingsFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    BuildingShell shell = parseBuilding(line);
                    if (shell == null) continue;

                    double[] centroid = shell.centroid();
                    double distance = haversine(lat, lon, centroid[1], centroid[0]);

                    if (!(distance <= radius) || !(distance < nearestDistance)) continue;
                    nearestDistance = distance;
                    nearest = shell;
                }
            } catch (IOException ignored) {
                // Skip unreadable tiles
            }
        }

        return nearest;
    }

    private BuildingShell parseBuilding(String jsonLine) {
        try {
            JsonObject obj = GSON.fromJson(jsonLine, JsonObject.class);
            String id = obj.get("id").getAsString();
            JsonObject properties = obj.getAsJsonObject("properties");
            String egid = properties.has("egid") && !properties.get("egid").isJsonNull() ? properties.get("egid").getAsString() : null;
            String uuid = properties.has("uuid") && !properties.get("uuid").isJsonNull() ? properties.get("uuid").getAsString() : null;

            JsonArray trianglesArray = obj.getAsJsonArray("triangles");
            List<BuildingShell.Triangle> triangles = new ArrayList<>(trianglesArray.size());

            for (int t = 0; t < trianglesArray.size(); t++) {
                JsonArray triArray = trianglesArray.get(t).getAsJsonArray();
                BuildingShell.Vertex[] vertices = new BuildingShell.Vertex[3];
                for (int v = 0; v < 3; v++) {
                    JsonArray ptArray = triArray.get(v).getAsJsonArray();
                    vertices[v] = new BuildingShell.Vertex(
                            ptArray.get(0).getAsDouble(),
                            ptArray.get(1).getAsDouble(),
                            ptArray.get(2).getAsDouble()
                    );
                }
                triangles.add(new BuildingShell.Triangle(vertices));
            }

            return new BuildingShell(id, egid, uuid, triangles);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Haversine distance in meters between two WGS84 points.
     */
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6_371_000.0d; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private record TileInfo(String name, double minLon, double minLat, double maxLon, double maxLat) {
        boolean intersects(double minLon, double minLat, double maxLon, double maxLat) {
            return this.minLon <= maxLon && this.maxLon >= minLon && this.minLat <= maxLat && this.maxLat >= minLat;
        }
    }
}
