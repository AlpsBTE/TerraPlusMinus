#!/usr/bin/env python3
"""
Preprocess SwissBuildings3D GDB tiles into JSONL for TerraPlusMinus.

Usage:
    python preprocess_swiss_buildings3d.py <input_dir> <output_dir>

Input directory should contain folders like:
    swissbuildings3d_3_0_2022_1135-42_2056_5728.gdb/
        swissBUILDINGS3D_3-0_1135-42.gdb/
            a00000001.gdbtable
            ...

Output structure:
    output_dir/
        tile_index.json
        1135-42/
            meta.json
            buildings.jsonl
        ...

Each building is stored as a JSON line with triangles in WGS84 and elevation in meters.
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
    print(
        "ERROR: GDAL Python bindings not available. Install with: pip install GDAL",
        file=sys.stderr,
    )
    sys.exit(1)


def create_lv95_to_wgs84_transform():
    """Create a coordinate transformation from LV95 (EPSG:2056) to WGS84 (EPSG:4326)."""
    source_srs = osr.SpatialReference()
    source_srs.ImportFromEPSG(2056)
    target_srs = osr.SpatialReference()
    target_srs.ImportFromEPSG(4326)
    # Force traditional GIS order (longitude, latitude)
    target_srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    return osr.CoordinateTransformation(source_srs, target_srs)


def extract_triangles_from_tin(tin_geom) -> list:
    """Extract all triangles from a TIN geometry as WGS84 [lon, lat, z] triples."""
    triangles = []
    for tri_idx in range(tin_geom.GetGeometryCount()):
        tri = tin_geom.GetGeometryRef(tri_idx)
        if tri is None or tri.GetGeometryName() != "TRIANGLE":
            continue
        # Triangle contains a single LinearRing
        ring = tri.GetGeometryRef(0)
        if ring is None or ring.GetPointCount() == 0:
            continue
        pts = []
        for p in range(ring.GetPointCount()):
            # GetPoint returns (x, y, z) after transformation
            x, y, z = ring.GetX(p), ring.GetY(p), ring.GetZ(p)
            pts.append([x, y, z])
        # Only store the first 3 points (ignore the closing duplicate)
        if len(pts) >= 3:
            triangles.append(pts[:3])
    return triangles


def process_tile(gdb_inner_path: Path, output_tile_dir: Path, transform) -> dict:
    """Process a single GDB tile and write its preprocessed JSONL."""
    ds = ogr.Open(str(gdb_inner_path))
    if ds is None:
        print(f"  ERROR: Could not open {gdb_inner_path}")
        return None

    layer = ds.GetLayerByName("Building_solid")
    if layer is None:
        print(f"  ERROR: Building_solid layer not found in {gdb_inner_path}")
        ds = None
        return None

    output_tile_dir.mkdir(parents=True, exist_ok=True)
    buildings_file = output_tile_dir / "buildings.jsonl"
    meta_file = output_tile_dir / "meta.json"

    buildings_count = 0
    min_lon, min_lat = float("inf"), float("inf")
    max_lon, max_lat = float("-inf"), float("-inf")

    with open(buildings_file, "w", encoding="utf-8") as f:
        layer.ResetReading()
        for feat in layer:
            geom = feat.GetGeometryRef()
            if geom is None or geom.GetGeometryName() != "TIN":
                continue

            # Clone geometry so we can transform it
            geom_clone = geom.Clone()
            geom_clone.Transform(transform)

            triangles = extract_triangles_from_tin(geom_clone)
            if not triangles:
                continue

            # Collect bounding box from all vertices
            feat_min_lon = float("inf")
            feat_min_lat = float("inf")
            feat_max_lon = float("-inf")
            feat_max_lat = float("-inf")

            for tri in triangles:
                for pt in tri:
                    lon, lat = pt[0], pt[1]
                    feat_min_lon = min(feat_min_lon, lon)
                    feat_min_lat = min(feat_min_lat, lat)
                    feat_max_lon = max(feat_max_lon, lon)
                    feat_max_lat = max(feat_max_lat, lat)

            if feat_min_lon == float("inf"):
                continue

            min_lon = min(min_lon, feat_min_lon)
            min_lat = min(min_lat, feat_min_lat)
            max_lon = max(max_lon, feat_max_lon)
            max_lat = max(max_lat, feat_max_lat)

            # Get ID fields
            egid = feat.GetField("EGID")
            uuid = feat.GetField("UUID")
            feature_id = (
                str(egid)
                if egid is not None
                else str(uuid)
                if uuid is not None
                else f"feat_{buildings_count}"
            )

            building = {
                "type": "Feature",
                "id": feature_id,
                "properties": {
                    "uuid": str(uuid) if uuid is not None else None,
                    "egid": str(egid) if egid is not None else None,
                },
                "triangles": triangles,
            }

            f.write(json.dumps(building, separators=(",", ":")) + "\n")
            buildings_count += 1

            geom_clone = None

    ds = None

    if buildings_count == 0:
        print(f"  No buildings found in {gdb_inner_path}")
        return None

    # Write meta.json
    meta = {
        "bounds": {
            "minLon": min_lon,
            "minLat": min_lat,
            "maxLon": max_lon,
            "maxLat": max_lat,
        },
        "buildings": buildings_count,
    }
    with open(meta_file, "w", encoding="utf-8") as f:
        json.dump(meta, f, separators=(",", ":"))

    print(f"  Processed {buildings_count} buildings -> {output_tile_dir.name}")
    return meta


def main():
    if len(sys.argv) < 3:
        print(
            "Usage: python preprocess_swiss_buildings3d.py <input_dir> <output_dir>",
            file=sys.stderr,
        )
        sys.exit(1)

    input_dir = Path(sys.argv[1])
    output_dir = Path(sys.argv[2])
    output_dir.mkdir(parents=True, exist_ok=True)

    transform = create_lv95_to_wgs84_transform()

    # Scan input directory for GDB tile folders
    tile_entries = []
    for gdb_outer in input_dir.iterdir():
        if not gdb_outer.is_dir() or not gdb_outer.suffix == ".gdb":
            continue
        # Look for the inner .gdb directory
        inner_dirs = [
            d for d in gdb_outer.iterdir() if d.is_dir() and d.suffix == ".gdb"
        ]
        if not inner_dirs:
            print(f"WARNING: No inner .gdb found in {gdb_outer}")
            continue
        inner_gdb = inner_dirs[0]
        # Derive a short tile name from the inner directory name, e.g. "1135-42"
        tile_name = inner_gdb.stem.replace("swissBUILDINGS3D_3-0_", "")
        tile_entries.append((inner_gdb, tile_name))

    print(f"Found {len(tile_entries)} tile(s) to process")

    tile_index = []
    for inner_gdb, tile_name in tile_entries:
        print(f"Processing tile: {tile_name} ...")
        tile_output_dir = output_dir / tile_name
        meta = process_tile(inner_gdb, tile_output_dir, transform)
        if meta:
            tile_index.append(
                {
                    "name": tile_name,
                    "bounds": meta["bounds"],
                }
            )

    # Write tile_index.json
    index_path = output_dir / "tile_index.json"
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump({"tiles": tile_index}, f, separators=(",", ":"), indent=2)

    print(f"\nDone! Processed {len(tile_index)} tile(s).")
    print(f"Index written to: {index_path}")


if __name__ == "__main__":
    main()
