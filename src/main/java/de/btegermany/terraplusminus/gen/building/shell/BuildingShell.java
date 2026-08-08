package de.btegermany.terraplusminus.gen.building.shell;

import java.util.List;
import lombok.NonNull;

public record BuildingShell(String datasetId, String id, String uuid, List<Triangle> triangles, Bounds bounds, Footprint footprint) {
    public BuildingShell {
        if (bounds == null) bounds = Bounds.fromTriangles(triangles);
        if (footprint == null) footprint = Footprint.fromBounds(bounds);
    }

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

    public boolean footprintOverlaps(BuildingShell other) {
        return this.footprint.overlaps(other.footprint);
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

    public record Footprint(@NonNull List<Ring> rings, Bounds bounds) {
        public static Footprint fromBounds(Bounds bounds) {
            List<Point> points = List.of(
                    new Point(bounds.minLon(), bounds.minLat()),
                    new Point(bounds.minLon(), bounds.maxLat()),
                    new Point(bounds.maxLon(), bounds.maxLat()),
                    new Point(bounds.maxLon(), bounds.minLat()),
                    new Point(bounds.minLon(), bounds.minLat())
            );
            return new Footprint(List.of(new Ring(points)), bounds);
        }

        public boolean overlaps(Footprint other) {
            if (!this.bounds.intersects2D(other.bounds.minLon(), other.bounds.minLat(), other.bounds.maxLon(), other.bounds.maxLat())) {
                return false;
            }
            for (Ring ring : this.rings) {
                for (Point point : ring.points) {
                    if (other.contains(point)) return true;
                }
            }
            for (Ring ring : other.rings) {
                for (Point point : ring.points) {
                    if (this.contains(point)) return true;
                }
            }
            return false;
        }

        private boolean contains(Point point) {
            for (Ring ring : this.rings) {
                if (ring.contains(point)) return true;
            }
            return false;
        }
    }

    public record Ring(@NonNull List<Point> points) {
        boolean contains(Point point) {
            boolean inside = false;
            for (int i = 0, j = this.points.size() - 1; i < this.points.size(); j = i++) {
                Point current = this.points.get(i);
                Point previous = this.points.get(j);

                if (pointOnSegment(point, previous, current)) return true;

                boolean intersects = ((current.lat() > point.lat()) != (previous.lat() > point.lat()))
                        && (point.lon() < (previous.lon() - current.lon()) * (point.lat() - current.lat()) / (previous.lat() - current.lat()) + current.lon());
                if (intersects) inside = !inside;
            }
            return inside;
        }

        private static boolean pointOnSegment(Point point, Point a, Point b) {
            double cross = (point.lon() - a.lon()) * (b.lat() - a.lat()) - (point.lat() - a.lat()) * (b.lon() - a.lon());
            if (Math.abs(cross) > 1.0e-12d) return false;

            double dot = (point.lon() - a.lon()) * (b.lon() - a.lon()) + (point.lat() - a.lat()) * (b.lat() - a.lat());
            if (dot < 0.0d) return false;

            double squaredLength = (b.lon() - a.lon()) * (b.lon() - a.lon()) + (b.lat() - a.lat()) * (b.lat() - a.lat());
            return dot <= squaredLength;
        }
    }

    public record Point(double lon, double lat) {
    }
}
