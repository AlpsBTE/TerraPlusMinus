#!/usr/bin/env python3
"""
Preprocess Swiss TLM3D building footprints into tiled GeoJSONSeq for TerraPlusMinus.

Usage:
    python preprocess_swiss_buildings.py <input.gpkg|input.gdb|input.geojson> <output_dir>

The input file must contain a layer with building footprints (e.g., TLM_GEBAEUDE_FOOTPRINT).
The output is a directory of GeoJSONSeq tiles, one Feature per line, ready to be loaded
by SwissBuildingDataset.

Tiles are indexed by 1/32 degree (~3.4km at Swiss latitudes) using equirectangular coordinates.
Only tiles that contain at least one building are written.

The script reprojects to WGS84 (EPSG:4326) automatically if needed.
"""

import json
import math
import os
import sys
from pathlib import Path

# Try to import GDAL bindings
try:
    from osgeo import ogr, osr

    HAS_GDAL = True
except ImportError:
    HAS_GDAL = False

# Tile size in degrees (1/32°)
TILE_SIZE = 1.0 / 32.0
INV_TILE_SIZE = 32.0


def lonlat_to_tile(lon: float, lat: float) -> tuple[int, int]:
    """Convert WGS84 lon/lat to tile indices using 1/32° equirectangular tiles."""
    tx = math.floor(lon * INV_TILE_SIZE)
    tz = math.floor(lat * INV_TILE_SIZE)
    return tx, tz


def extract_polygons_from_geom(geom) -> list:
    """Extract MultiPolygon coordinates from an OGR geometry."""
    geom_type = geom.GetGeometryName()
    polygons = []

    if geom_type == "MULTIPOLYGON":
        for i in range(geom.GetGeometryCount()):
            poly = geom.GetGeometryRef(i)
            rings = []
            for j in range(poly.GetGeometryCount()):
                ring = poly.GetGeometryRef(j)
                coords = []
                for k in range(ring.GetPointCount()):
                    # Note: OGR returns (x, y, z) - we drop z since Java parser ignores it
                    coords.append([ring.GetX(k), ring.GetY(k)])
                rings.append(coords)
            polygons.append(rings)
    elif geom_type == "POLYGON":
        rings = []
        for j in range(geom.GetGeometryCount()):
            ring = geom.GetGeometryRef(j)
            coords = []
            for k in range(ring.GetPointCount()):
                coords.append([ring.GetX(k), ring.GetY(k)])
            rings.append(coords)
        polygons.append(rings)

    return polygons


def process_with_gdal(input_path: str, output_dir: str, layer_name: str | None = None):
    if not HAS_GDAL:
        print(
            "ERROR: GDAL Python bindings not available. Install with: pip install GDAL",
            file=sys.stderr,
        )
        sys.exit(1)

    ds = ogr.Open(input_path)
    if ds is None:
        print(f"ERROR: Could not open {input_path}", file=sys.stderr)
        sys.exit(1)

    # Auto-detect layer if not specified
    if layer_name is None:
        layer = ds.GetLayerByIndex(0)
        layer_name = layer.GetName()
        print(f"Auto-detected layer: {layer_name}")
    else:
        layer = ds.GetLayerByName(layer_name)

    if layer is None:
        print(f"ERROR: Layer '{layer_name}' not found", file=sys.stderr)
        sys.exit(1)

    # Get spatial reference and create WGS84 transform if needed
    srs = layer.GetSpatialRef()
    target_srs = osr.SpatialReference()
    target_srs.ImportFromEPSG(4326)
    # Force traditional GIS order (longitude, latitude) instead of EPSG axis order (latitude, longitude)
    target_srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)

    transform = None
    if srs is not None:
        # Check if already WGS84
        if srs.GetAuthorityCode(None) == "4326":
            print("Input is already WGS84, no reprojection needed.")
        else:
            transform = osr.CoordinateTransformation(srs, target_srs)
            print(
                f"Reprojecting from EPSG:{srs.GetAuthorityCode(None)} to WGS84 (lon, lat)..."
            )
    else:
        print("WARNING: No spatial reference found, assuming WGS84.")

    # Build a dict of tiles -> list of features
    tiles: dict[tuple[int, int], list[dict]] = {}
    total_features = 0

    layer.ResetReading()
    for feat in layer:
        geom = feat.GetGeometryRef()
        if geom is None:
            continue

        # Clone geometry so we can transform it
        geom_clone = geom.Clone()
        if transform:
            geom_clone.Transform(transform)

        # Get bounding box in WGS84 to determine tile
        env = geom_clone.GetEnvelope()
        min_lon, max_lon, min_lat, max_lat = env[0], env[1], env[2], env[3]

        # Determine all tiles this feature intersects
        min_tx = math.floor(min_lon * INV_TILE_SIZE)
        max_tx = math.floor(max_lon * INV_TILE_SIZE)
        min_tz = math.floor(min_lat * INV_TILE_SIZE)
        max_tz = math.floor(max_lat * INV_TILE_SIZE)

        # Extract coordinates
        polygons = extract_polygons_from_geom(geom_clone)
        if not polygons:
            continue

        # Build GeoJSON geometry
        geometry_json = {"type": "MultiPolygon", "coordinates": polygons}

        # Get UUID or OBJECTID as id
        feature_id = None
        for name in ["UUID", "OBJECTID", "id", "EGID"]:
            idx = feat.GetFieldIndex(name)
            if idx >= 0:
                val = feat.GetField(idx)
                if val is not None:
                    feature_id = str(val)
                    break
        if feature_id is None:
            feature_id = f"feat_{total_features}"

        feature_json = {
            "type": "Feature",
            "id": feature_id,
            "geometry": geometry_json,
            "properties": {},
        }

        # Add feature to all intersecting tiles
        for tx in range(min_tx, max_tx + 1):
            for tz in range(min_tz, max_tz + 1):
                tiles.setdefault((tx, tz), []).append(feature_json)

        total_features += 1
        geom_clone = None

        if total_features % 10000 == 0:
            print(f"  Processed {total_features} features...")

    ds = None

    print(f"Total features: {total_features}")
    print(f"Total tiles: {len(tiles)}")

    # Write tiles
    out_path = Path(output_dir)
    out_path.mkdir(parents=True, exist_ok=True)

    for (tx, tz), features in tiles.items():
        tile_dir = out_path / "tile" / str(tx)
        tile_dir.mkdir(parents=True, exist_ok=True)
        tile_file = tile_dir / f"{tz}.json"

        with open(tile_file, "w", encoding="utf-8") as f:
            for feat in features:
                f.write(json.dumps(feat, separators=(",", ":")) + "\n")

    print(f"Done! Output written to {output_dir}")


def main():
    if len(sys.argv) < 3:
        print(
            "Usage: python preprocess_swiss_buildings.py <input.gpkg|input.gdb|input.geojson> <output_dir> [layer_name]",
            file=sys.stderr,
        )
        sys.exit(1)

    input_path = sys.argv[1]
    output_dir = sys.argv[2]
    layer_name = sys.argv[3] if len(sys.argv) > 3 else None

    process_with_gdal(input_path, output_dir, layer_name)


if __name__ == "__main__":
    main()
