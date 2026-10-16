# Tree height datasets

Optional. You can add json5 files to this directory to give trees their real height.
Without a json5 file, this dataset is off and any template of the chosen tree type can be placed.

Each pixel holds the tree height in metres (= blocks).

- **Height above 0:** a template of the chosen tree type is picked whose height is within `height_tolerance_percent`
  (in `../tree_rules.json5`, default 20) of the value. If no template is that close, the nearest ones are used.
- **0:** no tree grows here, even if the tree cover says there is one. Use it where nothing reaches tree height.
- **No value** (transparent pixel, missing tile, outside the `bounds`): any template of the tree type.

For more on the format, you can refer to the
[Terra++ wiki](https://github.com/BuildTheEarth/terraplusplus/wiki/Custom-terrain-dataset-guide).
