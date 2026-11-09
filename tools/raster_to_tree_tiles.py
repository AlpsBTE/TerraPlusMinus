#!/usr/bin/env python3
"""
Turn a raster into a TerraPlusMinus tree dataset: Web Mercator XYZ PNG tiles plus the json5 file for the plugin.

    tree_cover      input: vegetation height in m (classified with --min-height), or with --percent a tree cover
                    in percent (0-100). Tiles: tree cover in percent. Default zoom 17.
    tree_height     input: vegetation height in m. Tiles: height in m, 0 below --min-height. Default zoom 15.
    tree_leaf_type  input: broadleaf share in percent (0-100), or classes with --map. Tiles: broadleaf share in
                    percent (0 = only conifers, 100 = only broadleaf trees). Default zoom 14.

--map works for every dataset: it turns integer classes into tile values, e.g. --map 1=100,2=0. Classes that
are not listed get no value.

Usage:
    python raster_to_tree_tiles.py vegetation_height.tif out/cover --dataset tree_cover
    python raster_to_tree_tiles.py vegetation_height.tif out/height --dataset tree_height
    python raster_to_tree_tiles.py dlt.tif out/leaf --dataset tree_leaf_type --map 1=100,2=0 --mask country.gpkg
    python raster_to_tree_tiles.py west.tif east.tif out/leaf --dataset tree_leaf_type --map 1=0,2=50,3=100
    python raster_to_tree_tiles.py ndsm.vrt out/cover --dataset tree_cover --bbox 11.30 47.20 11.50 47.32

Input:
    Any raster GDAL can open, in any projection: GeoTIFF, VRT (gdalbuildvrt to combine many files), or a remote
    COG via /vsicurl/https://... Several inputs are allowed; where they overlap, the later one wins. The NoData
    value of the file is used (override it with --nodata). Merge, subtract (DSM - DTM) or clean the data with the
    GDAL tools first, see docs/tree-datasets.md.

Pipeline:
    The area is split into blocks of one Web Mercator tile at --block-zoom (default: 16 x 16 output tiles, or
    fewer for very fine input so that a block stays below ~8000 x 8000 input pixels). For each block:
    1. Read the input pixels around the block and turn them into tile values (0-254, 255 = no value). Outside the
       --mask boundary everything gets no value.
    2. Warp onto the Web Mercator grid of the output zoom: average for tree_cover and tree_leaf_type, max for
       tree_height.
    3. Write RGBA PNG tiles: the value in R, G and B (opaque), no value = transparent. Tiles without any value
       are not written. A gray PNG would not work: Java's ImageIO, which the plugin uses, gamma corrects it.
    4. Write work/blocks/<z>_<x>_<y>.json. It marks the block as done, so an interrupted run continues where it
       stopped. --force recomputes.
    Finally the json5 dataset file is written, with bounds from the data (or the mask / --bbox).

Output:
    <out_dir>/tiles/<zoom>/<x>/<y>.png
    <out_dir>/<dataset>/<name>.json5      copy to plugins/TerraPlusMinus/<dataset>/
    <out_dir>/work/                       done markers and run parameters

The json5 points to the tiles with a file:// URL by default. If the server reads them from another place, give
that place with --base-url (file:///..., or https://... for a web server).

Requires the GDAL Python bindings and numpy, e.g. the Python of QGIS (python-qgis.bat / python-qgis-ltr.bat on
Windows) or "conda install -c conda-forge gdal".
"""

import argparse
import json
import math
import multiprocessing
import os
import sys
import time
from pathlib import Path
from typing import NamedTuple

import numpy as np
from osgeo import gdal, ogr, osr

gdal.UseExceptions()
ogr.UseExceptions()


class Dataset(NamedTuple):
    zoom: int  # default zoom of the tiles
    resample: str  # resampling onto the Web Mercator grid
    max_value: int  # highest tile value
    label: str  # name of the value in the report
    meaning: str  # tile values, for the json5 comment ({divide} = --divide)


DATASETS = {
    "tree_cover": Dataset(17, "average", 100, "tree cover",
                          "tree cover in percent, divided by {divide:g} (sets the tree density)"),
    "tree_height": Dataset(15, "max", 254, "tree height", "tree height in metres (0 = no tree)"),
    "tree_leaf_type": Dataset(14, "average", 100, "broadleaf share",
                              "broadleaf share in percent (0 = only conifers, 100 = only broadleaf trees)"),
}

TILE_SIZE = 256
NODATA = 255  # tile value for "no value"
EARTH_CIRCUMFERENCE = 2 * math.pi * 6378137.0
MAX_BLOCK_PIXELS = 8000  # input pixels per block side for the automatic --block-zoom
# Max. error of GDAL's approximated transformation in pixels. With the default (0.125) the result depends on the
# block extent; 0.001 px is close to the exact transformation and much faster.
WARP_ERROR_THRESHOLD = 0.001
GDAL_CONFIG = {
    "GDAL_DISABLE_READDIR_ON_OPEN": "EMPTY_DIR",
    "GDAL_HTTP_MULTIRANGE": "YES",
    "GDAL_HTTP_MERGE_CONSECUTIVE_RANGES": "YES",
    "GDAL_HTTP_MAX_RETRY": "5",
    "GDAL_HTTP_RETRY_DELAY": "3",
    "GDAL_PAM_ENABLED": "NO",  # no .aux.xml files next to the inputs and tiles
}


def log(msg: str) -> None:
    print(msg, flush=True)


def configure_gdal() -> None:
    for key, value in GDAL_CONFIG.items():
        gdal.SetConfigOption(key, value)


# --- grids ----------------------------------------------------------------------------------------------------

def srs_from_epsg(epsg: int) -> osr.SpatialReference:
    srs = osr.SpatialReference()
    srs.ImportFromEPSG(epsg)
    srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    return srs


def srs_from_wkt(wkt: str) -> osr.SpatialReference:
    srs = osr.SpatialReference(wkt=wkt)
    srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    return srs


WGS84 = srs_from_epsg(4326)
MERCATOR = srs_from_epsg(3857)
MERCATOR_TO_WGS84 = osr.CoordinateTransformation(MERCATOR, WGS84)


def tile_bounds(z: int, x: int, y: int) -> tuple[float, float, float, float]:
    """Web Mercator bounds (min_x, min_y, max_x, max_y) of an XYZ tile."""
    size = EARTH_CIRCUMFERENCE / 2 ** z
    min_x = -EARTH_CIRCUMFERENCE / 2 + x * size
    max_y = EARTH_CIRCUMFERENCE / 2 - y * size
    return min_x, max_y - size, min_x + size, max_y


def tile_bounds_wgs84(block) -> tuple[float, float, float, float]:
    """WGS84 bounds (min_lon, min_lat, max_lon, max_lat) of an XYZ tile."""
    return MERCATOR_TO_WGS84.TransformBounds(*tile_bounds(*block), 21)


def blocks_for_lonlat(bbox, z: int) -> list[tuple[int, int, int]]:
    """All tiles (z, x, y) at zoom z that intersect a WGS84 box (min_lon, min_lat, max_lon, max_lat)."""
    min_lon, min_lat, max_lon, max_lat = bbox
    n = 2 ** z

    def tile(lon, lat):
        lat = max(min(lat, 85.05), -85.05)
        tx = int((lon + 180) / 360 * n)
        ty = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
        return min(max(tx, 0), n - 1), min(max(ty, 0), n - 1)

    x0, y0 = tile(min_lon, max_lat)
    x1, y1 = tile(max_lon, min_lat)
    return [(z, bx, by) for bx in range(x0, x1 + 1) for by in range(y0, y1 + 1)]


# boxes are (min_x, min_y, max_x, max_y)

def intersect(a, b):
    """Intersection of two boxes, None if they do not overlap."""
    r = (max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3]))
    return r if r[0] < r[2] and r[1] < r[3] else None


def union_boxes(boxes):
    return min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes)


def envelope_box(geom: ogr.Geometry):
    min_x, max_x, min_y, max_y = geom.GetEnvelope()
    return min_x, min_y, max_x, max_y


def box_polygon(box) -> ogr.Geometry:
    min_x, min_y, max_x, max_y = box
    return ogr.CreateGeometryFromWkt(f"POLYGON(({min_x} {min_y},{max_x} {min_y},{max_x} {max_y},{min_x} {max_y},"
                                     f"{min_x} {min_y}))")


def mem_raster(width: int, height: int, bands: int = 1, gt=None, srs: osr.SpatialReference | None = None):
    ds = gdal.GetDriverByName("MEM").Create("", width, height, bands, gdal.GDT_Byte)
    if gt is not None:
        ds.SetGeoTransform(gt)
    if srs is not None:
        ds.SetProjection(srs.ExportToWkt())
    return ds


class Input:
    """One input raster: its grid, projection and NoData value."""

    def __init__(self, path: str, band: int, nodata: float | None):
        self.path = path
        self.ds = gdal.Open(path)
        self.band = self.ds.GetRasterBand(band)
        gt = self.ds.GetGeoTransform()
        if gt[2] or gt[4]:
            raise SystemExit(f"{path}: rotated rasters are not supported, warp it to a north-up grid first")
        if not self.ds.GetProjection():
            raise SystemExit(f"{path}: the raster has no projection")
        self.gt = gt
        self.srs = srs_from_wkt(self.ds.GetProjection())
        self.from_mercator = osr.CoordinateTransformation(MERCATOR, self.srs)
        self.width, self.height = self.ds.RasterXSize, self.ds.RasterYSize
        self.nodata = nodata if nodata is not None else self.band.GetNoDataValue()

    def extent(self) -> tuple[float, float, float, float]:
        gt = self.gt
        xs = (gt[0], gt[0] + gt[1] * self.width)
        ys = (gt[3], gt[3] + gt[5] * self.height)
        return min(xs), min(ys), max(xs), max(ys)

    def extent_wgs84(self) -> tuple[float, float, float, float]:
        return osr.CoordinateTransformation(self.srs, WGS84).TransformBounds(*self.extent(), 21)

    def resolution_m(self) -> float:
        """Approximate pixel size in metres."""
        res = min(abs(self.gt[1]), abs(self.gt[5]))
        return res * 111320.0 if self.srs.IsGeographic() else res * self.srs.GetLinearUnits()

    def window(self, block) -> tuple[int, int, int, int] | None:
        """Pixel window (xoff, yoff, w, h) around a block, clipped to the raster. None if outside."""
        min_x, min_y, max_x, max_y = self.from_mercator.TransformBounds(*tile_bounds(*block), 21)
        gt = self.gt
        cols = sorted(((min_x - gt[0]) / gt[1], (max_x - gt[0]) / gt[1]))
        rows = sorted(((min_y - gt[3]) / gt[5], (max_y - gt[3]) / gt[5]))
        margin = 2  # pixels, so the warp has input up to the block edge
        x0 = max(0, math.floor(cols[0]) - margin)
        x1 = min(self.width, math.ceil(cols[1]) + margin)
        y0 = max(0, math.floor(rows[0]) - margin)
        y1 = min(self.height, math.ceil(rows[1]) + margin)
        if x1 <= x0 or y1 <= y0:
            return None
        return x0, y0, x1 - x0, y1 - y0


# --- values ---------------------------------------------------------------------------------------------------

def parse_map(text: str) -> dict[int, int]:
    """"1=100,2=0" -> {1: 100, 2: 0}"""
    result = {}
    for part in text.split(","):
        key, sep, value = part.partition("=")
        if not sep:
            raise argparse.ArgumentTypeError(f"expected class=value, got {part!r}")
        result[int(key)] = int(value)
    return result


def to_tile_values(arr: np.ndarray, valid: np.ndarray, args: argparse.Namespace) -> np.ndarray:
    """Input values -> tile values (Byte, NODATA = no value)."""
    out = np.full(arr.shape, NODATA, dtype=np.uint8)
    if args.map:
        for cls, value in args.map.items():
            out[valid & (arr == cls)] = value
        return out
    v = arr[valid]
    if args.dataset == "tree_height":
        out[valid] = np.where(v >= args.min_height, np.clip(np.rint(v), 1, 254), 0).astype(np.uint8)
    elif args.dataset == "tree_cover" and not args.percent:
        out[valid] = np.where(v >= args.min_height, 100, 0).astype(np.uint8)
    else:  # percent: values outside 0-100 (e.g. "unclassifiable" codes) get no value
        out[valid] = np.where((v >= 0) & (v <= 100), np.rint(v), NODATA).astype(np.uint8)
    return out


# --- mask -----------------------------------------------------------------------------------------------------

def load_mask(path: str, layer_name: str | None, where: str | None) -> ogr.Geometry:
    """Union of all (or the selected) polygons of a vector file, in WGS84."""
    ds = ogr.Open(path)
    layer = ds.GetLayerByName(layer_name) if layer_name else ds.GetLayer(0)
    if layer is None:
        raise SystemExit(f"{path}: no layer {layer_name!r}")
    if where:
        layer.SetAttributeFilter(where)
    srs = layer.GetSpatialRef()
    if srs is None:
        raise SystemExit(f"{path}: the layer has no projection")
    srs.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    union = ogr.Geometry(ogr.wkbMultiPolygon)
    for feature in layer:
        geom = feature.GetGeometryRef()
        if geom is None:
            continue
        geom = geom.Clone()
        geom.FlattenTo2D()
        if geom.GetGeometryType() == ogr.wkbPolygon:
            union.AddGeometry(geom)
        elif geom.GetGeometryType() == ogr.wkbMultiPolygon:
            for i in range(geom.GetGeometryCount()):
                union.AddGeometry(geom.GetGeometryRef(i))
    if union.IsEmpty():
        raise SystemExit(f"{path}: no polygons" + (f" for {where!r}" if where else ""))
    union = union.UnionCascaded()
    union.AssignSpatialReference(srs)
    union.Transform(osr.CoordinateTransformation(srs, WGS84))
    union.AssignSpatialReference(WGS84)
    return union


def rasterize(geom: ogr.Geometry, srs: osr.SpatialReference, gt, width: int, height: int) -> np.ndarray:
    """Boolean mask of a geometry (in srs) on a pixel grid. ALL_TOUCHED: no gap along the boundary."""
    mask_ds = mem_raster(width, height, gt=gt, srs=srs)
    vec = ogr.GetDriverByName("Memory").CreateDataSource("")
    layer = vec.CreateLayer("mask", srs, ogr.wkbMultiPolygon)
    feature = ogr.Feature(layer.GetLayerDefn())
    feature.SetGeometry(geom)
    layer.CreateFeature(feature)
    gdal.RasterizeLayer(mask_ds, [1], layer, burn_values=[1], options=["ALL_TOUCHED=TRUE"])
    return mask_ds.GetRasterBand(1).ReadAsArray() == 1


# --- block processing (worker processes) ----------------------------------------------------------------------

_worker = {}


def init_worker(args: argparse.Namespace, mask_wkb: bytes | None) -> None:
    configure_gdal()
    gdal.SetCacheMax(256 * 1024 * 1024)
    _worker["args"] = args
    _worker["inputs"] = inputs = [Input(p, args.band, args.nodata) for p in args.inputs]
    # the mask in the projection of each input, transformed once
    _worker["masks"] = [None] * len(inputs)
    if mask_wkb is not None:
        for i, inp in enumerate(inputs):
            geom = ogr.CreateGeometryFromWkb(mask_wkb)
            geom.AssignSpatialReference(WGS84)
            geom.Transform(osr.CoordinateTransformation(WGS84, inp.srs))
            geom.AssignSpatialReference(inp.srs)
            _worker["masks"][i] = geom


def read_values(inp: Input, block, args, mask: ogr.Geometry | None) -> gdal.Dataset | None:
    """Tile values of one input around a block, as an in-memory raster on the input grid, or None."""
    window = inp.window(block)
    if window is None:
        return None
    xoff, yoff, w, h = window
    arr = inp.band.ReadAsArray(xoff, yoff, w, h)
    valid = np.isfinite(arr) if np.issubdtype(arr.dtype, np.floating) else np.ones(arr.shape, dtype=bool)
    if inp.nodata is not None:
        valid &= arr != inp.nodata
    if not valid.any():
        return None
    gt = (inp.gt[0] + xoff * inp.gt[1], inp.gt[1], 0, inp.gt[3] + yoff * inp.gt[5], 0, inp.gt[5])
    if mask is not None:
        valid &= rasterize(mask, inp.srs, gt, w, h)
    values = to_tile_values(arr, valid, args)
    del arr, valid
    if (values == NODATA).all():
        return None
    ds = mem_raster(w, h, gt=gt, srs=inp.srs)
    ds.GetRasterBand(1).SetNoDataValue(NODATA)
    ds.GetRasterBand(1).WriteArray(values)
    return ds


def warp(sources: list[gdal.Dataset], block, zoom: int, dataset: Dataset) -> np.ndarray:
    size = TILE_SIZE * 2 ** (zoom - block[0])
    dst = gdal.Warp("", sources, format="MEM", dstSRS="EPSG:3857", outputBounds=tile_bounds(*block),
                    width=size, height=size, resampleAlg=dataset.resample, srcNodata=NODATA, dstNodata=-1,
                    outputType=gdal.GDT_Float32, errorThreshold=WARP_ERROR_THRESHOLD)
    arr = dst.GetRasterBand(1).ReadAsArray()
    dst = None
    return np.where(arr < 0, NODATA, np.clip(np.rint(arr), 0, dataset.max_value)).astype(np.uint8)


def write_tiles(out: np.ndarray | None, tiles_dir: Path, zoom: int, block) -> int:
    """Cut the block raster into XYZ tiles. Tiles without a value are not written, and an existing file of an
    earlier run is removed (no file = no value). out None: no value in the whole block. Returns the number of
    written tiles."""
    z, x, y = block
    n = 2 ** (zoom - z)
    png = gdal.GetDriverByName("PNG")
    written = 0
    for i in range(n):
        col_dir = tiles_dir / str(zoom) / str(x * n + i)
        for j in range(n):
            path = col_dir / f"{y * n + j}.png"
            tile = None if out is None else out[j * TILE_SIZE:(j + 1) * TILE_SIZE, i * TILE_SIZE:(i + 1) * TILE_SIZE]
            valid = None if tile is None else tile != NODATA
            if valid is None or not valid.any():
                if path.exists():
                    path.unlink()
                continue
            # value in R, G and B, opaque where there is a value
            ds = mem_raster(TILE_SIZE, TILE_SIZE, 4)
            gray = np.where(valid, tile, 0).astype(np.uint8)
            ds.WriteArray(np.stack([gray, gray, gray, np.where(valid, 255, 0).astype(np.uint8)]))
            col_dir.mkdir(parents=True, exist_ok=True)
            tmp = path.with_name(path.name + ".tmp")
            png.CreateCopy(str(tmp), ds, strict=0)
            ds = None
            os.replace(tmp, path)
            written += 1
    return written


def process_block(block) -> dict:
    args = _worker["args"]
    z, _, y = block
    t = time.time()
    result = {"block": list(block), "status": "empty", "tiles": 0, "valid_km2": 0.0, "mean": None, "histogram": {}}
    try:
        sources = [s for s in (read_values(inp, block, args, mask)
                               for inp, mask in zip(_worker["inputs"], _worker["masks"])) if s]
        out = warp(sources, block, args.zoom, DATASETS[args.dataset]) if sources else None
        sources = None
        if out is not None:
            valid = out != NODATA
            if valid.any():
                # ground pixel size shrinks with cos(latitude); the block center is good enough for statistics
                lat = math.atan(math.sinh(math.pi * (1 - 2 * (y + 0.5) / 2 ** z)))
                pixel_km2 = (EARTH_CIRCUMFERENCE / (TILE_SIZE * 2 ** args.zoom) * math.cos(lat)) ** 2 / 1e6
                values = out[valid]
                result["valid_km2"] = round(int(valid.sum()) * pixel_km2, 4)
                result["mean"] = round(float(values.mean()), 2)
                counts = np.bincount(values, minlength=1)
                result["histogram"] = {str(v): round(int(c) * pixel_km2, 6) for v, c in enumerate(counts) if c}
                result["status"] = "done"
        result["tiles"] = write_tiles(out, args.out_dir / "tiles", args.zoom, block)
    except Exception as e:  # reported by the main process, the block is retried on the next run
        return {"block": list(block), "status": "error", "error": f"{type(e).__name__}: {e}",
                "seconds": round(time.time() - t, 2)}
    result["seconds"] = round(time.time() - t, 2)
    write_json_atomic(marker(args.out_dir, block), result)
    return result


# --- bookkeeping ----------------------------------------------------------------------------------------------

def marker(out_dir: Path, block) -> Path:
    return out_dir / "work" / "blocks" / "{}_{}_{}.json".format(*block)


def write_json_atomic(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data), encoding="utf-8")
    os.replace(tmp, path)


def check_params(out_dir: Path, args: argparse.Namespace) -> None:
    """Refuse to continue a run with other parameters, which would mix incompatible blocks."""
    path = out_dir / "work" / "params.json"
    params = {"inputs": args.inputs, "dataset": args.dataset, "zoom": args.zoom, "block_zoom": args.block_zoom,
              "min_height": args.min_height, "percent": args.percent,
              "map": {str(k): v for k, v in args.map.items()} if args.map else None,
              "nodata": args.nodata, "band": args.band, "mask": args.mask, "mask_layer": args.mask_layer,
              "mask_where": args.mask_where}
    if path.exists() and not args.force:
        old = json.loads(path.read_text(encoding="utf-8"))
        if old != params:
            changed = sorted(k for k in params.keys() | old.keys() if old.get(k) != params.get(k))
            raise SystemExit(f"{path}: this run changes {', '.join(changed)}. Use the same parameters, another "
                             f"output directory, or --force to recompute all selected blocks.")
    write_json_atomic(path, params)


def fmt_duration(seconds: float) -> str:
    seconds = int(seconds)
    if seconds < 3600:
        return f"{seconds // 60}m{seconds % 60:02d}s"
    return f"{seconds // 3600}h{seconds // 60 % 60:02d}m"


def report(results: list[dict], dataset: str) -> None:
    done = [r for r in results if r["status"] == "done"]
    area = sum(r["valid_km2"] for r in done)
    log(f"report: {len(done)} blocks with data, {len(results) - len(done)} without, "
        f"{sum(r['tiles'] for r in results):,} tiles")
    if not area:
        return
    km2 = {}
    for r in done:
        for v, a in r["histogram"].items():
            km2[int(v)] = km2.get(int(v), 0.0) + a
    if dataset == "tree_height":
        trees = {v: a for v, a in km2.items() if v > 0}
        tree_area = sum(trees.values())
        log(f"  area with data: {area:,.1f} km2, with trees: {tree_area:,.1f} km2")
        if tree_area:
            log(f"  tree height: mean {sum(v * a for v, a in trees.items()) / tree_area:.1f} m, median "
                f"{percentile(trees, 0.5)} m, 90 %: {percentile(trees, 0.9)} m")
    else:
        mean = sum(v * a for v, a in km2.items()) / sum(km2.values())
        log(f"  area with data: {area:,.1f} km2, mean {DATASETS[dataset].label}: {mean:.1f} %")


def percentile(km2: dict[int, float], q: float) -> int:
    total = sum(km2.values())
    acc = 0.0
    for v in sorted(km2):
        acc += km2[v]
        if acc >= q * total:
            return v
    return max(km2)


def write_dataset_json5(args: argparse.Namespace, bounds_wgs84, base_url: str) -> Path:
    """The dataset file for plugins/TerraPlusMinus/<dataset>/ (plain JSON is valid json5)."""
    cfg_dir = args.out_dir / args.dataset
    cfg_dir.mkdir(parents=True, exist_ok=True)
    path = cfg_dir / f"{args.name}.json5"
    parse = {"from_int": {"delegate": {"grayscale_extract": {"delegate": {"parse_png": {}}}}}}
    if args.dataset == "tree_cover":
        # Terra-- expects the tree cover as a fraction; "divide" also sets the tree density
        parse = {"divide": {"delegate": parse, "value": args.divide}}
    min_lon, min_lat, max_lon, max_lat = bounds_wgs84
    config = [{
        "dataset": {
            "urls": [f"{base_url}/{args.zoom}/${{x}}/${{z}}.png"],
            "projection": {"web_mercator": {"zoom": args.zoom}},
            "resolution": TILE_SIZE,
            "blend": "NEAR",
            "parse": parse,
        },
        "zooms": 0,
        "bounds": {"minX": math.floor(min_lon * 100) / 100, "maxX": math.ceil(max_lon * 100) / 100,
                   "minZ": math.floor(min_lat * 100) / 100, "maxZ": math.ceil(max_lat * 100) / 100},
        "priority": args.priority,
    }]
    meaning = DATASETS[args.dataset].meaning.format(divide=args.divide)
    sources = ", ".join(Path(p).name for p in args.inputs)
    path.write_text(f"""/*
 * {args.dataset} dataset generated by tools/raster_to_tree_tiles.py from {sources}.
 * Copy this file to plugins/TerraPlusMinus/{args.dataset}/.
 * Tiles: {meaning}, Web Mercator zoom {args.zoom}.
 * Transparent pixels and missing tiles have no value.
 * Add the attribution the data licence asks for here.
 */
{json.dumps(config, indent=4)}
""", encoding="utf-8")
    return path


# --- main -----------------------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="+", metavar="INPUT... OUT_DIR",
                        help="one or more input rasters, then the output directory")
    parser.add_argument("--dataset", required=True, choices=sorted(DATASETS))
    parser.add_argument("--min-height", type=float, default=3.0,
                        help="tree_cover / tree_height: minimum vegetation height of a tree in m (default 3)")
    parser.add_argument("--percent", action="store_true",
                        help="tree_cover: the input already is a tree cover in percent (0-100), not heights")
    parser.add_argument("--map", type=parse_map, help="integer classes -> tile values, e.g. 1=100,2=0; "
                                                      "classes that are not listed get no value")
    parser.add_argument("--nodata", type=float, help="NoData value of the input (default: from the file)")
    parser.add_argument("--band", type=int, default=1, help="band of the input (default 1)")
    parser.add_argument("--mask", help="vector file (GeoPackage, GeoJSON, Shapefile, ...): values only inside "
                                       "its polygons, e.g. your country")
    parser.add_argument("--mask-layer", help="layer of the mask file (default: the first one)")
    parser.add_argument("--mask-where", help="attribute filter for the mask, e.g. \"iso = 'DE'\"")
    parser.add_argument("--bbox", nargs=4, type=float, metavar=("MIN_LON", "MIN_LAT", "MAX_LON", "MAX_LAT"),
                        help="only process the blocks that touch this WGS84 box (e.g. a test area)")
    parser.add_argument("--zoom", type=int, help="Web Mercator zoom of the tiles (default: " + ", ".join(
        f"{name} {d.zoom}" for name, d in DATASETS.items()) + ")")
    parser.add_argument("--block-zoom", type=int, help="zoom of a processing block (default: automatic)")
    parser.add_argument("--name", help="name of the json5 file (default: name of the first input)")
    parser.add_argument("--priority", type=float, default=100.0,
                        help="dataset priority; where datasets overlap, the higher one wins (default 100)")
    parser.add_argument("--divide", type=float, default=400.0,
                        help="tree_cover: divisor of the percent values in the json5, sets the tree density: "
                             "lower = more trees (default 400, ~6 trees per chunk at 100 %% cover)")
    parser.add_argument("--base-url", help="URL of <out_dir>/tiles as the server sees it "
                                           "(default: file:// URL of <out_dir>/tiles)")
    parser.add_argument("--processes", type=int, default=max(1, (os.cpu_count() or 2) // 2))
    parser.add_argument("--force", action="store_true", help="recompute blocks that are already done")
    args = parser.parse_args()

    if len(args.paths) < 2:
        parser.error("give at least one input and the output directory")
    args.inputs, args.out_dir = args.paths[:-1], Path(args.paths[-1]).resolve()
    dataset = DATASETS[args.dataset]
    if args.percent and args.dataset != "tree_cover":
        parser.error("--percent is only for tree_cover")
    if args.map and not all(0 <= v <= dataset.max_value for v in args.map.values()):
        parser.error(f"--map values must be between 0 and {dataset.max_value}")
    if args.zoom is None:
        args.zoom = dataset.zoom
    if args.name is None:
        args.name = Path(args.inputs[0]).stem
    configure_gdal()

    inputs = [Input(p, args.band, args.nodata) for p in args.inputs]
    for inp in inputs:
        log(f"input {inp.path}: {inp.width} x {inp.height} px, ~{inp.resolution_m():.2f} m, "
            f"{inp.srs.GetAuthorityName(None) or ''}:{inp.srs.GetAuthorityCode(None) or '?'}, NoData {inp.nodata}")
        if inp.nodata is None and not args.map:
            log("  warning: no NoData value, every pixel counts as data (set one with --nodata)")

    # area: inputs, limited by the mask and --bbox
    data_area = union_boxes([inp.extent_wgs84() for inp in inputs])
    mask = None
    if args.mask:
        mask = load_mask(args.mask, args.mask_layer, args.mask_where)
        data_area = intersect(data_area, envelope_box(mask))
    area = intersect(data_area, tuple(args.bbox)) if args.bbox and data_area else data_area
    if area is None:
        raise SystemExit("the inputs, --mask and --bbox do not overlap")

    if args.block_zoom is None:
        # 16 x 16 tiles per block, smaller where that would be too many input pixels
        lat = math.radians((area[1] + area[3]) / 2)
        res = min(inp.resolution_m() for inp in inputs)
        args.block_zoom = max(0, args.zoom - 4)
        while args.block_zoom < args.zoom and \
                EARTH_CIRCUMFERENCE * math.cos(lat) / 2 ** args.block_zoom / res > MAX_BLOCK_PIXELS:
            args.block_zoom += 1
    if not 0 <= args.block_zoom <= args.zoom:
        parser.error(f"--block-zoom must be between 0 and the zoom ({args.zoom})")

    block_boxes = {b: tile_bounds_wgs84(b) for b in blocks_for_lonlat(area, args.block_zoom)}
    if mask is not None:
        block_boxes = {b: box for b, box in block_boxes.items() if box_polygon(box).Intersects(mask)}
    blocks = list(block_boxes)
    check_params(args.out_dir, args)
    todo, results = [], []
    for b in blocks:
        m = marker(args.out_dir, b)
        if m.exists() and not args.force:
            results.append(json.loads(m.read_text(encoding="utf-8")))
        else:
            todo.append(b)
    log(f"{args.dataset} at zoom {args.zoom}: {len(blocks)} blocks at zoom {args.block_zoom}, "
        f"{len(blocks) - len(todo)} already done, {len(todo)} to process")

    errors = 0
    if todo:
        processes = max(1, min(args.processes, len(todo)))
        log(f"processing with {processes} processes ...")
        start = time.time()
        mask_wkb = bytes(mask.ExportToWkb()) if mask is not None else None
        with multiprocessing.Pool(processes, initializer=init_worker, initargs=(args, mask_wkb)) as pool:
            for i, r in enumerate(pool.imap_unordered(process_block, todo), 1):
                z, x, y = r["block"]
                if r["status"] == "error":
                    errors += 1
                    log(f"[{i}/{len(todo)}] {z}/{x}/{y}  ERROR {r['error']}")
                    continue
                results.append(r)
                eta = (time.time() - start) / i * (len(todo) - i)
                mean = f"mean {r['mean']:5.1f}" if r["mean"] is not None else "          "
                log(f"[{i}/{len(todo)}] {z}/{x}/{y}  {r['seconds']:6.1f} s  {r['status']:5s}  {mean}  "
                    f"{r['tiles']:3d} tiles  {r['valid_km2']:7.2f} km2  ETA {fmt_duration(eta)}")
        log(f"finished in {fmt_duration(time.time() - start)}"
            + (f", {errors} blocks failed: run again to retry them" if errors else ""))

    base_url = (args.base_url or "file://" + (args.out_dir / "tiles").as_posix()).rstrip("/")
    # bounds: the data, limited to the processed blocks (whole blocks are written, also with --bbox)
    bounds = (intersect(data_area, union_boxes(block_boxes.values())) if blocks else None) or data_area
    cfg = write_dataset_json5(args, bounds, base_url)
    log(f"dataset file: {cfg}")
    report(results, args.dataset)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
