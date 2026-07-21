# DRAFT upstream Issue — do not post without review

Target: https://github.com/Helioviewer-Project/JHelioviewer-SWHV/issues
Author posts manually. This is a proposal/negotiation, not a PR drop.

---

## Title
Proposal: a "Point Cloud" layer for scattered 3D data, with alpha-shape surfaces

## Body

I'd like to propose a small plugin that renders a scattered 3D point cloud over
the Sun, colored by a per-point data value, and optionally draws a surface
recovered from the points with the alpha-shape method (Edelsbrunner & Mücke
1994). The motivating use is thin, sheet-like distributions — e.g. an isosurface
of a modeled field available only as scattered samples — where the convex hull
is the wrong answer (it fills every concavity) but a single-parameter alpha-shape
tracks the folds.

**Why it can be tiny in JHV.** The client never triangulates. A producer ships
the Delaunay tetrahedra and their circumradii alongside the points; an alpha
slider then selects the tetrahedra with circumradius below the threshold and
extracts the boundary faces (faces owned by exactly one selected tet). That is an
O(#tets) prefix-sort-and-count — ~15 ms for 10^5 tets on this laptop — so the
slider is real-time and there is no new dependency. The convex hull falls out at
alpha = 100% and, as a cross-check, reproduces `scipy`'s hull triangle-for-count.

**Shape of the contribution.** A self-contained plugin under
`plugins/pointcloud/` (mirrors the PFSS plugin: layer + off-thread worker +
options panel + loader), plus one registration line in
`JHelioviewer.loadPlugins()`. Points render through `GLSLShape.renderPoints`,
the wireframe through `GLSLLine`, an optional translucent surface through
`GLSLShape.renderShape(TRIANGLES)` with culling disabled. File format is a small
gzipped JSON (`"type":"PointCloud"`: flat `points`/`values`/`tets`/`radii`
arrays), parsed with the bundled fastjson2, in the spirit of the existing SunJSON
type. State serializes/restores like the other layers; a `TimeMap` gates by
viewpoint time so clouds can form a series.

**A one-line companion.** GOES-R SUVI is recognized as an image source but has no
FOV-catalog entry; I've added one (`suvi-fov` branch) so the cloud's placement in
the SUVI field can be shown. Happy to send that on its own regardless of this
proposal's fate.

I have a working proof of concept (screenshots/short video below) on my fork, and
can split it into reviewable PRs — (1) SUVI FOV one-liner, (2) points-only layer
+ loader, (3) alpha wireframe + slider, (4) translucent surface — or restructure
however you'd prefer. Before I open PRs I wanted to check whether this belongs in
core JHV at all, and if so how you'd like it scoped.

[attach: screenshots of the fabric cloud over SUVI at alpha = 100 / 92 / low, and
the rotate-to-show-limb-occlusion frame]

---
### References
- H. Edelsbrunner, E.P. Mücke, "Three-Dimensional Alpha Shapes," ACM Trans.
  Graphics 13(1):43–72, 1994. doi:10.1145/174462.156635
