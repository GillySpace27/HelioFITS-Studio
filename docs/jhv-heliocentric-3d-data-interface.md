---
title: |
   | SWHV CCNx
   | JHV Heliocentric 3D data interface
subtitle: SWHV-ROB-IF-001-CCNx v0.9
subject: SWHV CCN4
date: SWHV-ROB-IF-001-CCNx - Version 0.9 - 2026-08-31
lof: false
lot: false
---

# Introduction

`id: \exec{git hash-object \file}`

This document defines two ways to supply heliocentric 3D geometry to JHelioviewer (JHV): SunJSON and glTF 2.0. In
both cases, the producer selects the model quantities to display and converts them into geometry and colors. JHV then
places and renders the product together with its other solar data layers.

SunJSON is a small, JHV-specific JSON format for points, connected lines, and ellipses in Carrington spherical
coordinates. It also records an observation time and JHV-specific display sizes. SunJSON was introduced to display
model output throughout the heliosphere and is used by Qorona for its JHV field-line products.

glTF is a general 3D asset standard for products that need triangle surfaces, textures, materials, a static object
hierarchy, or a combination of surfaces, lines, and points. Its Cartesian geometry can be displayed in general glTF
viewers, while solar metadata added to the asset tells JHV how to place it.

To show how the interfaces can be used in practice, the document includes a practical example based on COCONUT
output. The same interfaces are intended for suitable products from other models.

For products made only from points, lines, and ellipses, SunJSON is usually the more direct route. For meshed surfaces
or products intended to remain portable outside JHV, use glTF.

# SunJSON

SunJSON was inspired by GeoJSON's simple organization of geometry, but it is a separate heliocentric format. Each
file contains one timestamp and a list of geometry entries:

```json
{
  "type": "SunJSON",
  "time": "2025-10-09T18:19:52.000",
  "geometry": [
    {
      "type": "line",
      "coordinates": [[1.1, 135, 45], [1.2, 135, 45], [1.3, 135, 45]],
      "colors": [[255, 255, 255, 255], [255, 0, 0, 255], [0, 0, 255, 255]],
      "thickness": 0.016
    },
    {
      "type": "point",
      "coordinates": [[1.1, 45, 45], [1.3, 45, 45]],
      "colors": [[255, 180, 0, 255]],
      "thickness": 0.01
    },
    {
      "type": "ellipse",
      "coordinates": [[2, 60, 0], [1, 60, 90], [3, 60, 0]],
      "colors": [[255, 0, 0, 192]],
      "thickness": 0.004
    }
  ]
}
```

## File structure

The top-level fields are:

- `type`: required and must be `SunJSON`;
- `time`: required timestamp in the form `YYYY-MM-DDTHH:mm:ss`, optionally followed by fractional seconds. JHV
  assumes UTC and does not accept timezone designators or numeric offsets; and
- `geometry`: required array of geometry entries. It may contain points, lines, and ellipses in any order.

## Geometry and appearance

Every geometry entry has four fields:

- `type`: exactly `point`, `line`, or `ellipse`;
- `coordinates`: an array of finite three-component coordinates;
- `colors`: an array of four-component colors; and
- `thickness`: a finite number controlling the rendered line thickness or point size.

Each coordinate is `[radius, Carrington longitude, Carrington latitude]`, where radius is the heliocentric distance
in solar radii and both angles are in degrees. JHV converts these spherical coordinates directly into the Carrington
frame. A radius below one solar radius is accepted but produces a warning because it places the coordinate beneath
the nominal solar surface.

The geometry types interpret their coordinates as follows:

- A `point` entry needs at least one coordinate and produces one point for each coordinate.
- A `line` entry needs at least two coordinates. JHV joins consecutive coordinates into one connected polyline.
- An `ellipse` entry needs exactly three coordinates. After conversion to Cartesian points, let them be $C$, $U$,
  and $V$. JHV draws the closed curve $C + (U - C)\cos(t) + (V - C)\sin(t)$ for $0 \le t \le 2\pi$. Here, $C$ is
  the center, while the offsets $U - C$ and $V - C$ define the curve's two Cartesian directions and lengths.

Each color is straight, non-premultiplied RGBA, with integer components from 0 through 255. JHV clamps
values outside that range and converts the result to its premultiplied representation. When fewer colors than
coordinates are supplied, the last color is repeated. Extra colors are ignored, and an ellipse uses only its first
color. The `colors` field must be present, although an empty array may be used to select JHV's default green.

`thickness` is a JHV display parameter, not a physical width measured in solar radii. JHV currently clamps it to the
range `0.00001` through `0.1` and uses the same field to control point size for a `point` entry. The relationship
between this value and the resulting on-screen width or size is implementation-dependent, so producers should check
that the chosen value gives a suitable result in JHV.

## Loading and time selection

JHV loads SunJSON from local files and HTTP URIs. Dragging a local `.json` file or an HTTP URI ending in
`.json` into JHV selects the SunJSON loader. A SAMP client can instead send `jhv.load.sunjson` with either a `url`
parameter or a `value` parameter containing the complete JSON text. The data is stored in the Connection layer and
drawn only when that layer is enabled.

Each file represents one timestamp. When several files are loaded, JHV treats them as a time sequence and displays
the file whose timestamp is nearest to the current JHV time.

SunJSON deliberately has a narrow scope: it has no triangle surfaces, textures, materials, object hierarchy, or
portable definition of its display sizes. Use glTF when a product needs those features.

# glTF

## glTF and GLB

glTF has two file forms:

- `.gltf` stores the scene description as JSON and may refer to separate binary buffers and images or embed them as
  Base64 data;
- `.glb` packages the JSON and binary resources in one file.

GLB is convenient for distribution and avoids Base64 expansion: its binary payload is about 25% smaller than the same
payload embedded as Base64, but has no size advantage over a `.gltf` file with external binary resources.

Either form can be gzip-compressed without altering its content. JHV accepts `.gltf.gz` and `.glb.gz`. An HTTP server
may instead use `Content-Encoding: gzip` while transferring the unmodified asset. Compression complements rather
than replaces careful geometry simplification or decimation where accuracy permits. Reducing the geometry also
lowers memory use after loading and the amount of work needed to render the asset.

## Heliocentric Cartesian coordinates

A glTF `POSITION` attribute always contains Cartesian `x`, `y`, and `z` components. Storing spherical tuples such as
`[radius, longitude, latitude]` in this attribute would therefore distort the asset in standard viewers. Producers
whose source data is spherical must convert it to Cartesian positions before export.

The local Cartesian axes follow the heliocentric convention described by Thompson (2006):

- `SOLX` points toward solar west in the observer's image plane;
- `SOLY` points toward solar north in the observer's image plane;
- `SOLZ` points from Sun center toward the observer.

The coordinate origin is the center of the Sun. `CRLN_OBS` and `CRLT_OBS`, described below, give the Carrington
direction that defines the local axes. For a physical observation this is the direction of the observer, while a
model product that is not tied to a viewpoint may use a reference direction matching its native axes. At
`CRLN_OBS = 0` and `CRLT_OBS = 0`, `SOLZ` points toward Carrington longitude zero in the solar equatorial plane,
`SOLX` points toward Carrington longitude 90 degrees, and `SOLY` points north. Other products may declare a non-zero
direction, which JHV uses to rotate their local coordinates into its Carrington world frame.

JHV's glTF profile requires positions in solar radii and uses a physical solar radius of 695700000 meters. The
three glTF position components therefore have the following fixed declaration:

```text
CTYPE1 = SOLX    CUNIT1 = solRad
CTYPE2 = SOLY    CUNIT2 = solRad
CTYPE3 = SOLZ    CUNIT3 = solRad
RSUN_REF = 695700000.0
WCSNAME = Heliocentric-cartesian
```

## Scene metadata

glTF does not define solar coordinates or observation frames. Store the following declaration in `extras` on the
default glTF scene:

```json
{
  "scene": 0,
  "scenes": [
    {
      "name": "coronal model",
      "extras": {
        "DATE-OBS": "2025-10-09T18:19:52.000",
        "DSUN_OBS": 149597870700.0,
        "CRLN_OBS": 0.0,
        "CRLT_OBS": 0.0,
        "RSUN_REF": 695700000.0,
        "CTYPE1": "SOLX", "CUNIT1": "solRad",
        "CTYPE2": "SOLY", "CUNIT2": "solRad",
        "CTYPE3": "SOLZ", "CUNIT3": "solRad",
        "WCSNAME": "Heliocentric-cartesian"
      }
    }
  ]
}
```

`DATE-OBS` is the observation or model-state time in the form `YYYY-MM-DDTHH:mm:ss`, optionally followed by fractional
seconds. JHV assumes UTC and does not accept timezone designators or numeric offsets. Every heliocentric glTF product
must include `DATE-OBS`, which identifies its place in a model time sequence.

`CRLN_OBS` and `CRLT_OBS` give the Carrington longitude and latitude of the local `SOLZ` direction at `DATE-OBS`, in
degrees, and thereby define the orientation of all three Cartesian axes. They may describe a physical observer or a
reference direction chosen for a model product. In either case, the positions must be expressed in the corresponding
`SOLX`, `SOLY`, and `SOLZ` frame. Latitude must be between -90 and 90 degrees.

`DSUN_OBS` gives the distance of the observer or reference point from Sun center in meters. A model product using only
a reference direction may omit it or supply a conventional positive distance. JHV validates the value when it is
present but does not currently use it to place the geometry.

A file either supplies the complete JHV scene metadata or none of it. `WCSNAME` identifies the declaration to JHV.
When it is present, JHV requires and validates `DATE-OBS`, `CTYPE1` through `CTYPE3`, `CUNIT1` through `CUNIT3`,
`RSUN_REF`, `CRLN_OBS`, and `CRLT_OBS`, together with optional `DSUN_OBS`. Without `WCSNAME`, an ordinary glTF asset
instead receives default metadata: JHV treats its positions as world coordinates and assigns the application-start
time, as it does for an image without metadata. This fallback lies outside the heliocentric interface defined here.

JHV applies this placement to triangles, lines, and points. Rotating the JHV view changes the camera, not the
product's coordinates, whereas other glTF viewers ignore the solar metadata and display the local Cartesian geometry.

## Supported glTF content

JHV uses Assimp, the Open Asset Import Library, to read both glTF file forms, but glTF can describe more than JHV can
display. This section summarizes the geometry and appearance that JHV supports.

**Geometry and scene structure.** JHV renders open or closed triangle surfaces, connected lines and polylines, and
point sets. It applies the translations, rotations, and scales from the static node hierarchy, including each use of a
mesh referenced by more than one node, before placing the resulting geometry in the solar scene. Animations, skins,
and morph targets are not supported.

Because glTF does not define a portable line width or point size, JHV uses fixed values for both. SunJSON is the
better choice when a JHV-specific product needs to control these display sizes.

**Colors, materials, and textures.** Triangles, lines, and points may use a material base color and per-vertex RGBA
colors, while triangle materials may additionally use one base-color texture, either embedded in the asset or stored
beside a `.gltf` file. Textures are not applied to lines or points.

glTF colors use straight alpha, so producers must write ordinary, non-premultiplied values, which JHV converts to the
premultiplied representation used by its renderer. JHV supports opaque (`OPAQUE`), cut-out (`MASK`), and translucent
(`BLEND`) materials. On triangles, `MASK` discards fragments below the material's alpha cutoff, whereas on lines and
points JHV applies the cutoff to vertex colors before rendering, making transitions along a line segment only
approximate. Additive blending, separate opacity textures, and transformed texture coordinates are not supported.

**Lighting and surfaces.** JHV applies simple directional shading to triangle surfaces unless their material uses the
`KHR_materials_unlit` extension. Because shading reveals surface shape by changing the apparent brightness of its
colors, materials whose colors encode values that must remain unchanged should be marked as unlit.

Lit triangle meshes should include vertex normals that describe the intended surface. If they are absent, Assimp
generates smooth normals while loading the asset, which may smooth edges that were meant to remain sharp, whereas
unlit materials do not need normals. JHV does not implement the complete glTF metallic/roughness model, normal maps,
emissive materials, or lights and cameras stored in the asset.

JHV respects single- and double-sided triangle materials, making a double-sided surface visible from either side while
a single-sided surface disappears when viewed from its back. Opaque surfaces use the depth buffer normally, and
ordinary alpha blending is available for translucent surfaces, but JHV cannot always determine the correct drawing
order for overlapping or intersecting translucent geometry. JHV sorts whole translucent meshes back to front by their
centers, not individual triangles. Export spatially separate translucent components as separate glTF mesh primitives
so JHV can order them independently. This does not resolve intersecting surfaces or self-overlap within a mesh.

**Preparing a product.** JHV renders the colors stored in the glTF file. When those colors represent a physical
quantity, the producer must map its values to colors before export and describe that mapping in scene `extras`,
including the quantity, units, value range, and color map.

When accuracy allows, dense lines should be simplified and dense triangle meshes decimated before export, using a
recorded geometric or data-aware error criterion that preserves important boundaries and attributes. Comparing the
reduced product with the full result then confirms that the reduction is acceptable, while careful reduction can
substantially reduce file size, transfer time, and rendering cost.

Before distributing a product, inspect it from several viewpoints in JHV, including its interaction with the solar
sphere and other layers, and open the same asset in an independent glTF viewer. These checks can reveal placement,
surface orientation, transparency, and portability problems that structural validation alone cannot find.

The capabilities described here reflect the products considered so far and provide a practical starting point. As
new real-world products require additional ways to represent or display their data, JHV's glTF support can be
expanded where feasible.

## COCONUT example

`extra/test/create_coconut_scene.py` demonstrates the supported glTF capabilities and provides a starting point for
using Qorona, PyVista/VTK, and pygltflib to produce a JHV-compatible asset from model output. It is tailored to the
supplied COCONUT sample CFmesh and is not intended as a general COCONUT exporter. The resulting
`coconut-corona-scene.glb` contains:

- unlit magnetic field lines colored by polarity;
- an unlit triangulated `B_r=0` current-sheet surface colored by radial plasma velocity;
- unlit point markers at the inner and outer boundary endpoints of complete open field lines; and
- eleven selected closed field lines represented by lit, thick yellow tubes with smooth vertex normals.

The tube centerlines follow field lines traced from the supplied background magnetic field. Their selection, radius,
color, and representation as solid tubes are artificial choices included to show mesh normals and lighting. They
demonstrate a construction that could be adapted to visualize model-derived flux ropes.

The current sheet is unlit because its color map represents radial plasma velocity. Shading it would change the
brightness according to surface orientation, making the same velocity appear as different colors across the mesh.
The field-line and boundary-point materials are also marked as unlit because their colors identify magnetic polarity,
although JHV does not shade line or point primitives. The tubes, in contrast, have a constant illustrative color and
remain lit so that their round cross-section and three-dimensional shape are visible.

Qorona can export field lines directly as SunJSON, whereas this example uses glTF to combine lines, surfaces, and
points in one asset that general glTF viewers can also display.

\newpage

Run the converter from the repository root in an environment containing Qorona 0.4.0, PyVista/VTK, Matplotlib, and
pygltflib:

```shell
python extra/test/create_coconut_scene.py \
    /path/to/coconut_corona.CFmesh.xz \
    --timestamp 2025-10-09T18:19:52 \
    --output extra/test/data/coconut-corona-scene.glb
```

The CFmesh file does not identify its observation time or coordinate frame. The converter therefore uses the
following assumptions and settings:

- for this CFmesh, the Qorona Cartesian coordinates are assumed to be Carrington-aligned, with `+x` at Carrington
  longitude zero, `+y` at Carrington longitude 90 degrees, and `+z` toward solar north;
- the output uses the reference direction `CRLN_OBS = 0`, `CRLT_OBS = 0` and records a conventional `DSUN_OBS` of
  1 au. The converter therefore writes `(SOLX, SOLY, SOLZ) = (y, z, x)`. This fixed axis reordering preserves the
  model's Carrington orientation and does not depend on Earth's position at the supplied time;
- the native cells are reconstructed with Qorona's degree-1 moving-least-squares resampler on a logarithmic
  `192 x 180 x 360` spherical grid spanning 1 to 6 solar radii;
- field lines are traced in float64 with DOPRI5, `rtol=10^-8`, `cfl=0.125`, and an `18 x 36` seed grid. Their glTF
  positions are stored as float32;
- after tracing, the field-line paths are simplified with the Ramer-Douglas-Peucker algorithm so that no removed
  trace vertex is farther than `10^-5` solar radii (about 7 km for the adopted solar radius) from the simplified
  path. The same step is applied to the centerlines used to make the tubes in this example;
- both boundary endpoints of each complete open field line are exported as points, with the line's polarity color;
- eleven additional closed field lines are traced from equally spaced seeds at Carrington latitude 6 degrees and
  longitudes 32 through 42 degrees, then converted to 4 Mm-radius tubes with 16 sides;
- the current sheet is extracted as the `B_r=0` isosurface of the reconstructed magnetic field, matching the
  geometric definition used by Guo et al. (2024, Fig. 1d), with the periodic longitude seam closed before meshing;
- the model velocity is interpolated at each surface vertex, converted with the COOLFluiD `corona` normalization
  `v0 = 480 km/s`, and projected onto the local radial direction;
- the extracted current-sheet mesh is reduced by a target of 50 percent with scalar-aware quadric decimation, using
  radial velocity in the decimation error metric. Vertex colors are generated from the resulting scalar values after
  this step; and
- radial velocity is mapped through the `turbo` color map over -30 to 300 km/s, with a common surface alpha of 0.35.

The default scene's `extras` records the source name and SHA-256 digest, Qorona version, processing parameters,
surface definition, velocity mapping, and geometry counts before and after display-geometry post-processing. These
steps reduce the product size without lowering the reconstruction grid or changing the field-line tracing tolerances.

\newpage

PyVista and VTK create the geometry and return a glTF document in memory. The script adds the solar metadata and uses
pygltflib to package it directly as GLB. Colors are normalized unsigned-byte straight RGBA values, and the current
sheet is double-sided and alpha-blended. The field-line, current-sheet, and point materials are marked with
`KHR_materials_unlit`, while the tube material remains lit and includes one smooth normal per vertex.

### Validation

The converter checks its input arrays before export, then reopens the completed GLB and verifies its structure and
content. The checks cover the default scene and exact metadata, the single embedded binary buffer, the expected line,
triangle, and point primitives, and their accessor and index counts. They also confirm finite float32 positions,
non-degenerate adjacent line segments, normalized RGBA attributes, the intended lit and unlit materials, a complete
normal for every tube vertex, the current sheet's colors, alpha, and double-sided material, and boundary points at both
model limits in both polarity colors.

# References

- W. T. Thompson, [Coordinate systems for solar image data](https://doi.org/10.1051/0004-6361:20054262),
  *Astronomy & Astrophysics* 449 (2006), 791-803
- Qorona documentation: [Export to JHelioviewer](https://rayandhib.github.io/Qorona/jhelioviewer/) and
  [field lines](https://rayandhib.github.io/Qorona/products/fieldlines/)
- J. H. Guo et al., [Modeling the propagation of coronal mass ejections with COCONUT: Implementation of the
  regularized Biot-Savart law flux rope model](https://doi.org/10.1051/0004-6361/202347634), *Astronomy &
  Astrophysics* 683 (2024), A54
- [Khronos glTF Registry and current specification](https://registry.khronos.org/glTF/)
- [Open Asset Import Library (Assimp)](https://www.assimp.org/)
- [VTK `vtkGLTFExporter`](https://vtk.org/doc/nightly/html/classvtkGLTFExporter.html)
- [pygltflib](https://github.com/avaturn/pygltflib)
