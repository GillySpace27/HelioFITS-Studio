# Point Cloud layer — how to see it running

JHV is already running from this build (launched into `/tmp/jhv-pc-home`). If you
closed it:

    cd ~/Documents/NWRA/PUNCH_Science/jhv-pointcloud
    ant run

The demo cloud files are in the scratchpad and copied to `/tmp`:
- `/tmp/fabric_suvi.json.gz`  — the PointCloud file for the new layer
- `fabric_suvi_sunjson.json`  — a plain SunJSON points file (stock JHV, Phase-0 check)

Regenerate them any time with `python make_fabric_suvi.py` (needs sunpy).

## Load the cloud

1. In the Layers panel, the **Point Cloud** layer is present (added by the plugin).
   Select it to show its options.
2. Click **Open…** and choose `/tmp/fabric_suvi.json.gz`.
3. The 15000-point rippled sheet appears over the Sun, colored by mid-surface
   height (Rainbow LUT). It sits at the sub-Earth point for 2026-07-15T12:00, in
   plane-of-sky the SUVI field (±1.1 R☉, inside SUVI's ±1.65).

## What to check (matches the plan's verification list)

- **Alpha slider** at 100 → convex hull (a smooth slab, 294 triangles); drag down
  to ~92 → the ripples resolve; lower → the sheet breaks up. Smooth while
  dragging (mesh build is off-thread, keep-latest). The label shows the resolved
  alpha in R☉.
- **Points / Wireframe / Surface** toggles; **Size** and **Opacity** spinners;
  **Colormap** combo; **Color by data** toggle.
- **Rotate the camera**: the cloud occludes correctly behind the disk/limb, and
  the sheet reads as 3D in profile.
- **Surface** on: translucent skin, visible from both sides (culling disabled),
  no black fringing (premultiplied alpha).
- **Save state / restart / restore**: the layer returns with the same file, alpha,
  and colormap.
- **SUVI FOV**: on the `suvi-fov` branch, the FOV layer's Earth-orbit list gains a
  SUVI box that frames the cloud.

## Already verified headlessly (no GUI needed)

- Loader parses the real 15000-point / 98481-tet file; all validation passes.
- Boundary extraction reproduces the scipy convex hull exactly (294 triangles at
  alpha = 100%); build times 1.5–29 ms across the alpha range (< 50 ms target).
- Serialize → restore round-trips every option field.
- Clean `ant compile` (no warnings) and clean launch (no exceptions in the log).

## Branches on your fork (pushed, nothing opened upstream)

- `feat-pointcloud` — the plugin (8 new files + 1 line in JHelioviewer.java)
- `suvi-fov` — the one-line SUVI FOV catalog entry (off fresh upstream/master)

Draft upstream Issue text: `POINTCLOUD_UPSTREAM_ISSUE.md` (post yourself after review).
