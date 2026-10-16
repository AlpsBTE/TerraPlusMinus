# Tree leaf type datasets

Optional. You can add json5 files to this directory to tell TerraPlusMinus where trees are broadleaf or conifer.
Without a json5 file, this dataset is off and `broadleaf_percent` from `../tree_rules.json5` is used everywhere.

Each pixel holds the broadleaf share in percent: 0 = only conifers, 100 = only broadleaf trees.

- **Value:** replaces `broadleaf_percent` of the tree rule at that location.
  The rule still decides which tree types grow: the value only chooses between the rule's `broadleaf` and `conifer`
  types. If the rule has no types on the chosen side, the other side is used.
- **No value** (transparent pixel, missing tile, outside the `bounds`): `broadleaf_percent` of the rule.

For more on the format, you can refer to the
[Terra++ wiki](https://github.com/BuildTheEarth/terraplusplus/wiki/Custom-terrain-dataset-guide).
