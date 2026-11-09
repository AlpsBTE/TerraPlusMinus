# Creating your own tree datasets

TerraPlusMinus places trees from three datasets. You can add your own data for each of them, for example from a
national laser scan or a forest map of your country:

| Dataset          | Folder in `plugins/TerraPlusMinus/` | Pixel value                                   | Without your data                     |
|------------------|-------------------------------------|-----------------------------------------------|---------------------------------------|
| `tree_cover`     | `tree_cover/`                       | tree cover in percent: how many trees grow    | Terra--'s global tree cover           |
| `tree_height`    | `tree_height/`                      | tree height in metres (= blocks), 0 = no tree | any tree of the chosen type           |
| `tree_leaf_type` | `tree_leaf_type/`                   | broadleaf share in percent (0 = conifers)     | `broadleaf_percent` of the tree rules |

Which tree types exist and where they grow is set in `customTrees.json` and `tree_rules.json5`. The datasets only
say *where* trees grow, *how tall* they are and whether they are *broadleaf or conifer*.

A dataset is a set of map tiles (Web Mercator XYZ PNG tiles, like a web map) plus a small `.json5` file that tells
the plugin where the tiles are. The easiest way to make both is the helper script
[`tools/raster_to_tree_tiles.py`](../tools/raster_to_tree_tiles.py): give it a raster file, get the tiles and the
`.json5` file.

- [What you need](#what-you-need)
- [Quick start](#quick-start)
- [Getting the source data](#getting-the-source-data)
- [Examples](#examples)
- [Using the dataset on the server](#using-the-dataset-on-the-server)
- [Several datasets: priority and bounds](#several-datasets-priority-and-bounds)
- [The tile format](#the-tile-format)
- [Troubleshooting](#troubleshooting)

## What you need

- **GDAL with Python bindings and numpy.** The simplest way on Windows is [QGIS](https://qgis.org): it comes with
  both. Run the scripts with the QGIS Python, e.g. `"C:\Program Files\QGIS 3.34.14\bin\python-qgis-ltr.bat"` (the
  folder name contains your QGIS version; `python-qgis.bat` for non-LTR versions), or use the *OSGeo4W Shell* from the start menu, where `python` and the GDAL command line tools
  (`gdalbuildvrt`, `gdalinfo`, ...) work directly. On Linux / macOS: the `gdal` package of your system
  or `conda install -c conda-forge gdal numpy`.
- **A raster with your data**, in any projection: GeoTIFF, a remote Cloud Optimized GeoTIFF, or anything else GDAL
  can open. See [Getting the source data](#getting-the-source-data).
- Disk space: tiles are small (a few KB each), but a whole country at zoom 17 is millions of files.

## Quick start

```
python tools/raster_to_tree_tiles.py <input raster(s)> <output folder> --dataset <tree_cover|tree_height|tree_leaf_type> [options]
```

What the input must contain depends on the dataset:

| Dataset          | Input                                                       | Option                       |
|------------------|-------------------------------------------------------------|------------------------------|
| `tree_cover`     | vegetation height in metres (e.g. a canopy height model)    | `--min-height 3` (default)   |
| `tree_cover`     | tree cover in percent (0-100)                               | `--percent`                  |
| `tree_height`    | vegetation height in metres                                 | `--min-height 3` (default)   |
| `tree_leaf_type` | broadleaf share in percent (0-100)                          | none                         |

Try it on a small area first with `--bbox MIN_LON MIN_LAT MAX_LON MAX_LAT`, check the result in game, then run the
whole area. Useful options:

| Option | Meaning |
|---|---|
| `--mask country.gpkg` | values only inside the polygons of a vector file, e.g. your country. Add `--mask-where "name = 'Bayern'"` to pick one polygon and `--mask-layer` if the file has several layers |
| `--min-height 3` | vegetation lower than this (m) is not a tree: no cover, height 0 |
| `--nodata -9999` | NoData value of the input, if the file does not have one set (the script warns) |
| `--zoom` | tile zoom (default: cover 17, height 15, leaf type 14, see [the tile format](#the-tile-format)) |
| `--priority 100` | which dataset wins where several overlap, see [priority and bounds](#several-datasets-priority-and-bounds) |
| `--divide 400` | `tree_cover` only: tree density, lower = more trees, see [tree density](#tree-density) |
| `--base-url` | where the server finds the tiles, see [Using the dataset on the server](#using-the-dataset-on-the-server) |
| `--name` | name of the `.json5` file |
| `--processes 8` | parallel processes |
| `--force` | recompute everything (otherwise an interrupted run continues where it stopped) |

Run `python tools/raster_to_tree_tiles.py --help` for all options.

The output folder then contains:

```
<output folder>/tiles/<zoom>/<x>/<y>.png     the tiles
<output folder>/<dataset>/<name>.json5       copy to plugins/TerraPlusMinus/<dataset>/
<output folder>/work/                        progress markers (can be deleted when you are done)
```

Use a new output folder for every dataset and input. A folder remembers the options of its first run, so that an
interrupted run can continue, and refuses a run with other options.

## Getting the source data

Look for open data from the national mapping or forest agency of your country first. In order of quality:

**Tree cover and tree height**

- **Airborne laser scan (lidar) height models.** Many countries and states publish a *DSM* (digital surface model,
  top of trees and buildings) and a *DTM* (digital terrain model, the ground). DSM minus DTM is the height of
  everything above the ground (*nDSM*, *canopy height model*). This is the best source: it finds every single tree.
  Some agencies publish a ready-made vegetation height model, which is even better because buildings are removed.
  Buildings in an nDSM are not a problem in practice: Terra-- does not place trees on or next to OpenStreetMap
  buildings.
- **Copernicus High Resolution Layer *Tree Cover Density*** (10 m, all of Europe): tree cover in percent, use it
  with `--percent`. Download from the [Copernicus Land Monitoring Service](https://land.copernicus.eu).
- **Global canopy height maps** (e.g. Meta / WRI 1 m, ETH 10 m): available everywhere, but much less exact than
  lidar, see [the example](#tree-cover-and-height-from-a-global-canopy-height-map).

**Leaf type**

- **National forest type or tree species maps** (classes like conifer / mixed / broadleaf): use `--map`.
- **Copernicus High Resolution Layer *Dominant Leaf Type*** (10 m, all of Europe): classes 1 = broadleaf,
  2 = conifer, so `--map 1=100,2=0`. It also covers single trees outside the forest, so it is a good gap filler below
  a national forest map.

Always check the license: most open data needs an attribution. Write it into the comment at the top of the generated
`.json5` file and credit the source on your server or website.

### Preparing the data with GDAL

The helper reads one or more rasters. If your data needs work first, the GDAL command line tools do most things:

- **Many files → one input:** `gdalbuildvrt all.vrt tiles/*.tif` builds a small virtual mosaic without copying the
  data. Pass `all.vrt` to the helper. Where files overlap, the last one wins.
- **DSM − DTM:** both must have the same grid (they usually do when they come from the same agency):
  ```
  python -m osgeo_utils.gdal_calc -A dsm.vrt -B dtm.vrt --calc "maximum(A - B, 0)" --outfile ndsm.tif --type Float32 --NoDataValue -9999 --co COMPRESS=DEFLATE --co TILED=YES --co BIGTIFF=IF_SAFER
  ```
  (`python -m osgeo_utils.gdal_calc` works everywhere; `gdal_calc` / `gdal_calc.py` also work on many systems,
  but not in the QGIS 3.34 shell on Windows.) For a large area, do this per file pair and combine the results with
  `gdalbuildvrt`.
- **Remote files:** GDAL reads Cloud Optimized GeoTIFFs directly from a web server with
  `/vsicurl/https://.../file.tif`, so you do not have to download whole files. A local copy is faster if you run the
  same area several times.
- **Look at the data:** `gdalinfo file.tif` shows the projection, pixel size and NoData value; QGIS shows the values.

## Examples

### Leaf type for your country from Copernicus Dominant Leaf Type

1. Download *Dominant Leaf Type 2018* (10 m) for your country from the
   [Copernicus Land Monitoring Service](https://land.copernicus.eu). It comes as several GeoTIFF files; combine them
   (adapt the file pattern to the names of your download): `gdalbuildvrt dlt.vrt DLT_2018_010m_*.tif`
2. Get a boundary of your country as a vector file (GeoPackage, GeoJSON, Shapefile), e.g. from your mapping agency
   or [geoBoundaries](https://www.geoboundaries.org) (detailed enough for 10 m data; Natural Earth is too coarse at
   the border). Without a mask the whole input area gets values, including
   the neighboring countries.
3. Run:
   ```
   python tools/raster_to_tree_tiles.py dlt.vrt out/leaf_type --dataset tree_leaf_type --map 1=100,2=0 --mask country.gpkg --name my_country_leaf_type --processes 8
   ```
   Class 0 (no tree), 254 (unclassifiable) and 255 (outside) are not in `--map`, so they get no value: there the
   `broadleaf_percent` of the tree rule applies.
4. Copy `out/leaf_type/tree_leaf_type/my_country_leaf_type.json5` to `plugins/TerraPlusMinus/tree_leaf_type/`.

If you also have a national forest map that is better inside the forest, make a dataset from it with a higher
`--priority` (e.g. 90) and keep the Dominant Leaf Type at a lower one (e.g. 70) for the trees outside the forest.

### Tree cover and tree height from a laser scan

```
gdalbuildvrt dsm.vrt dsm/*.tif
gdalbuildvrt dtm.vrt dtm/*.tif
python -m osgeo_utils.gdal_calc -A dsm.vrt -B dtm.vrt --calc "maximum(A - B, 0)" --outfile ndsm.tif --type Float32 --NoDataValue -9999 --co COMPRESS=DEFLATE --co TILED=YES --co BIGTIFF=IF_SAFER

python tools/raster_to_tree_tiles.py ndsm.tif out/cover  --dataset tree_cover  --name my_state_tree_cover --processes 8
python tools/raster_to_tree_tiles.py ndsm.tif out/height --dataset tree_height --name my_state_tree_height --processes 8
```

Copy `out/cover/tree_cover/my_state_tree_cover.json5` to `plugins/TerraPlusMinus/tree_cover/` and
`out/height/tree_height/my_state_tree_height.json5` to `plugins/TerraPlusMinus/tree_height/`.

`--min-height 3` (the default) counts everything from 3 m as a tree. Raise it if hedges and bushes become trees,
lower it for young forests. A 0.5-1 m height model is ideal; with 2 m or coarser data, tree heights come out a bit
lower than they are.

Some agencies publish the nDSM directly, then the `gdal_calc` step is not needed. Example: North Rhine-Westphalia
(Germany) publishes `ndom50`, a 0.5 m nDSM in 1 km tiles, on [opengeodata.nrw.de](https://www.opengeodata.nrw.de).
The tiles along the state border also cover the neighbouring areas, so mask them with the state boundary, e.g. from
geoBoundaries (`geoBoundaries-DEU-ADM1.geojson`):

```
gdalbuildvrt ndom.vrt ndom50_*.tif
python tools/raster_to_tree_tiles.py ndom.vrt out/nrw_cover --dataset tree_cover --mask geoBoundaries-DEU-ADM1.geojson --mask-where "shapeName = 'Nordrhein-Westfalen'" --name nrw_tree_cover
```

### Tree cover from Copernicus Tree Cover Density

```
gdalbuildvrt tcd.vrt TCD_2018_010m_*.tif
python tools/raster_to_tree_tiles.py tcd.vrt out/tcd_cover --dataset tree_cover --percent --zoom 15 --mask country.gpkg --name my_country_tree_cover
```

10 m data has no single trees, so zoom 15 is enough. Values 254 / 255 (unclassifiable / outside) are no value.

Expect a smoother result than from a laser scan: in a test area the mean cover was the same as from a 0.5 m nDSM
(64 % vs. 65 %), but open land got about 30 % instead of 0 % and closed forest about 80 % instead of 100 %. Trees
are spread more evenly over forest edges, fields and gardens. Good as a fallback where there is no laser scan.

### Tree cover and height from a global canopy height map

Where there is no laser scan, a global canopy height map gives both datasets. Example: the Meta / WRI
*High Resolution Canopy Height Maps* (1 m, open data on AWS, `tiles.geojson` in
`https://dataforgood-fb-data.s3.amazonaws.com/forests/v1/alsgedi_global_v6_float/` lists the tiles). The tiles can
be read remotely; heights are whole metres and the files have no NoData value (0 = ground, which is correct here,
so ignore the warning):

```
python tools/raster_to_tree_tiles.py /vsicurl/https://dataforgood-fb-data.s3.amazonaws.com/forests/v1/alsgedi_global_v6_float/chm/<tile>.tif out/meta_height --dataset tree_height --mask my_area.gpkg
python tools/raster_to_tree_tiles.py /vsicurl/https://dataforgood-fb-data.s3.amazonaws.com/forests/v1/alsgedi_global_v6_float/chm/<tile>.tif out/meta_cover --dataset tree_cover --mask my_area.gpkg
```

The files are stored line by line, so reading them remotely is slow for large areas: for more than a few blocks,
download the tile first.

Compared with a 0.5 m nDSM in a test area, the trees were about 5 m lower (median 22 m vs. 28 m) and the cover was
higher, especially on open land (about 50 % instead of 0 %). Try a higher `--min-height` (e.g. 5) and a higher
`divide` for the cover.

## Using the dataset on the server

The `.json5` file contains the URL of the tiles. By default it is a `file://` URL of the output folder on the
computer that ran the script. If the tiles are somewhere else on the server, give that place when you run the
script, or edit the URL in the `.json5` file later:

```
--base-url file:///home/container/tree_data/cover/tiles        tiles in a folder on the server
--base-url https://example.org/trees/cover/tiles               tiles on a web server
```

The URL in the file ends with `/<zoom>/${x}/${z}.png`, where `${x}` and `${z}` are the tile column and row.

Copy the `.json5` file into the dataset folder (`plugins/TerraPlusMinus/tree_cover/` etc.) and restart the server.
The log shows `Dataset tree_leaf_type: on (1 datasets)` for leaf type and height. Only newly generated chunks use the
data.

## Several datasets: priority and bounds

Each `.json5` file has `bounds` (WGS84, `minX`/`maxX` = longitude, `minZ`/`maxZ` = latitude) and a `priority`. For
each location the plugin uses the dataset with the highest priority that has a value there; where it has no value
(transparent pixel, missing tile, outside its bounds) the next one is used, down to the default.

So "no value" and "0" mean different things:

- **0** is a real value: no tree cover, a conifer stand, height 0 (= no tree).
- **No value** lets the next dataset (or the default) decide.

The helper writes 0 where your data says "no trees" and no value only where your data has no data, so inside your
area your data wins and outside of it the next dataset takes over. If datasets of neighboring areas overlap at the
border, give the better one the higher priority.

### Tree density

Terra-- reads the `tree_cover` value as a fraction, and `divide` in the `.json5` file turns the percent of the tiles
into it. It also sets how many trees grow: with the default 400, 100 % cover gives about 6 trees per chunk, which is
close to a closed European forest (150-250 canopy trees per hectare). Lower values give more trees, higher values
fewer. You can change `divide` in the `.json5` file later without making the tiles again.

## The tile format

You only need this if you make tiles without the helper script.

- **Grid:** Web Mercator (EPSG:3857) XYZ tiles, 256 x 256 px, at one zoom level, `<zoom>/<x>/<y>.png`
  (y counted from the north, like OpenStreetMap tiles).
- **Values:** one byte per pixel, written to the R, G and B channels of an **RGBA** PNG, alpha 255.
  `tree_cover` and `tree_leaf_type`: 0-100, `tree_height`: metres (0-254).
- **No value:** alpha 0 (transparent), or no tile file at all.
- **Do not write gray or gray + alpha PNGs.** Java's ImageIO, which the plugin uses to read PNGs, gamma corrects
  them and the values come out wrong (100 is read as 168). This is also why the output of `gdal2tiles` does not work
  for a one-band raster.
- **Resampling** when you warp to Web Mercator: *average* for cover and leaf type, *maximum* for height (an average
  would make the trees lower).
- **Zoom:** fine enough for the data, not finer. The pixel size is ~1.2 m × cos(latitude) at zoom 17, doubling with
  every zoom level below. Defaults: cover 17 (single trees), height 15, leaf type 14 (10 m source data).

The `.json5` file for a dataset whose tiles hold the value itself (`tree_height`, `tree_leaf_type`):

```json5
[
    {
        "dataset": {
            "urls": ["file:///path/to/tiles/14/${x}/${z}.png"],
            "projection": { "web_mercator": { "zoom": 14 } },
            "resolution": 256,
            "blend": "NEAR",
            "parse": { "from_int": { "delegate": { "grayscale_extract": { "delegate": { "parse_png": {} } } } } }
        },
        "zooms": 0,
        "bounds": { "minX": 9.47, "maxX": 9.64, "minZ": 47.04, "maxZ": 47.28 },
        "priority": 100
    }
]
```

For `tree_cover`, wrap the parser in `divide`:

```json5
"parse": { "divide": { "delegate": { "from_int": { "delegate": { "grayscale_extract": { "delegate": { "parse_png": {} } } } } }, "value": 400 } }
```

More about the dataset format: [Terra++ custom dataset guide](https://github.com/BuildTheEarth/terraplusplus/wiki/Custom-terrain-dataset-guide).

## Troubleshooting

- **No trees / the dataset has no effect:** check the server log for the dataset line and errors, check that the
  URL in the `.json5` file points to the tiles (open one tile in a browser or image viewer), and that you look at
  newly generated chunks.
- **Trees where there should be none, or everywhere in a rectangle:** the input has no NoData value, so the empty
  area around the data counts as data. Set it with `--nodata`.
- **Too many / too few trees:** change `divide` (cover) or `--min-height`.
- **The script stops with "this run changes ...":** an output folder belongs to one set of options (and one input).
  Use a new folder, or `--force` to recompute.
- **Windows, Git Bash:** a `/vsicurl/...` argument is turned into a Windows path by Git Bash, and quoted arguments
  like `--mask-where "name = 'X'"` do not reach `python-qgis*.bat` intact. Use the OSGeo4W Shell, or run
  `export MSYS_NO_PATHCONV=1` first and avoid quoted arguments.
- **`--bbox` writes more than the box:** the work is done in blocks (one Web Mercator tile of several km), and
  every block that touches the box is processed completely. The `bounds` in the `.json5` file cover these blocks.
