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

To show how the interfaces can be used in practice, the document includes a worked conversion of COCONUT output. The
same interfaces are intended for suitable products from other models.

For line-only products, SunJSON is usually the more direct route. For meshed surfaces or products intended to remain
portable outside JHV, use glTF.

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
  assumes UTC and does not accept timezone designators or numeric offsets;
- `geometry`: required array of geometry entries. It may contain points, lines, and ellipses in any order.

## Geometry and appearance

Every geometry entry has four fields:

- `type`: exactly `point`, `line`, or `ellipse`;
- `coordinates`: an array of finite three-component coordinates;
- `colors`: an array of four-component colors; and
- `thickness`: a finite number controlling the rendered line thickness or point size.

Each coordinate is `[radius, Carrington longitude, Carrington latitude]`, where radius is the heliocentric distance
in solar radii and both angles are in degrees. JHV converts these spherical coordinates directly into the Carrington
frame; a radius below one solar radius is accepted but produces a warning because it places the coordinate beneath
the nominal solar surface.

The geometry types interpret their coordinates as follows:

- A `point` entry needs at least one coordinate and produces one point for each coordinate.
- A `line` entry needs at least two coordinates. JHV joins consecutive coordinates into one connected polyline.
- An `ellipse` entry needs exactly three coordinates. After conversion to Cartesian points, let them be $C$, $U$,
  and $V$. JHV draws the closed curve $C + (U - C)\cos(t) + (V - C)\sin(t)$ for $0 \le t \le 2\pi$. $C$ is the center;
  the offsets $U - C$ and $V - C$ define the curve's two Cartesian directions and lengths.

Each color is straight, non-premultiplied `[R, G, B, A]`, with integer components from 0 through 255. JHV clamps
values outside that range and converts the result to its premultiplied representation. When fewer colors than
coordinates are supplied, the last color is repeated; extra colors are ignored, and an ellipse uses only its first
color. The `colors` field must be present, although an empty array may be used to select JHV's default green.

`thickness` is a JHV display parameter, not a physical width measured in solar radii. JHV currently clamps it to the
range `0.00001` through `0.1` and uses the same field to control point size for a `point` entry. The relationship
between this value and the resulting on-screen width or size is implementation-dependent, so producers should check
that the chosen value gives a suitable result in JHV.

## Loading and time selection

JHV loads SunJSON from local files and HTTP(S) URIs. Dragging a `.json` file or URI into JHV selects the SunJSON
loader; a SAMP client can instead send `jhv.load.sunjson` with either a `url` parameter or a `value` parameter
containing the complete JSON text. The data is stored in the Connection layer and drawn only when that layer is
enabled.

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

Either form can be gzip-compressed without altering its content. JHV accepts `.gltf.gz` and `.glb.gz`; an HTTP server
may instead use `Content-Encoding: gzip` while transferring the unmodified asset. Compression complements rather
than replaces careful simplification or decimation where accuracy permits; geometric reduction also lowers the
decoded size and rendering cost.

## Heliocentric Cartesian coordinates

A glTF `POSITION` attribute always contains Cartesian `x`, `y`, and `z` components. Storing spherical tuples such as
`[radius, longitude, latitude]` in this attribute would therefore distort the asset in standard viewers. Producers
whose source data is spherical must convert it to Cartesian positions before export.

The local Cartesian axes follow the heliocentric convention described by Thompson (2006):

- `SOLX` points toward solar west in the observer's image plane;
- `SOLY` points toward solar north in the observer's image plane;
- `SOLZ` points from Sun center toward the observer.

The origin is Sun center in an observer-aligned Cartesian frame, not a Carrington Cartesian frame.

JHV's current glTF profile requires positions in solar radii and uses a physical solar radius of 695700000 meters. The
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
        "DSUN_OBS": 149000000000.0,
        "CRLN_OBS": 123.4,
        "CRLT_OBS": 5.6,
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
seconds. JHV assumes UTC; timezone designators and numeric offsets are not accepted.
`CRLN_OBS` and `CRLT_OBS` are the observer's Carrington longitude and latitude at `DATE-OBS`, in degrees; latitude must
be between -90 and 90 degrees.

`DSUN_OBS` records the observer distance from Sun center in meters and is retained as observer provenance. JHV
currently neither validates it nor uses it to place the geometry.

To position an asset in heliocentric coordinates, supply the complete positioning declaration: `WCSNAME`,
`CTYPE1` through `CTYPE3`, `CUNIT1` through `CUNIT3`, `RSUN_REF`, `CRLN_OBS`, `CRLT_OBS`, and `DATE-OBS`. Supplying
only part of this declaration is an error. An asset without it can still be loaded, but its positions are treated as
JHV world coordinates. `DATE-OBS` may also be supplied by itself to give such an asset an observation time.

JHV uses the observer longitude and latitude to rotate the declared `SOLX/SOLY/SOLZ` positions into Carrington
coordinates, applying the same placement to triangles, lines, and points. Rotating the JHV view changes the camera,
not the product's coordinates, whereas other glTF viewers ignore the solar metadata and display the local Cartesian
geometry.

## Supported glTF content

JHV uses Assimp, the Open Asset Import Library, to read both glTF file forms. JHV currently supports:

- triangle meshes, including open or closed surfaces;
- connected lines and polylines;
- point sets;
- static node translations, rotations, and scales, including repeated use of a mesh;
- base colors and per-vertex RGBA colors on triangles, lines, and points;
- one base-color texture per triangle material, embedded in the asset or stored beside a `.gltf` file;
- opaque, cut-out (`MASK`), and translucent (`BLEND`) materials;
- single- and double-sided triangle surfaces; and
- unlit materials, whose colors are displayed without lighting or shading.

glTF colors use straight alpha, so producers must write ordinary, non-premultiplied values; JHV converts them to the
premultiplied representation used by its renderer.

JHV applies simple directional shading to triangle surfaces unless a material is marked as *unlit*. The shading makes
the surface shape easier to see, but it also changes the apparent brightness of the colors. Use the glTF
`KHR_materials_unlit` extension when encoded values must be displayed unchanged. JHV does not implement the complete
glTF metallic/roughness lighting model, normal maps, emissive materials, or lights and cameras stored in the asset.

For predictable lighting, lit triangle meshes should provide vertex normals describing the intended surface. If they
are absent, Assimp generates smooth normals while loading the asset, which may smooth edges that the producer intended
to remain sharp. Unlit materials do not need normals.

Because glTF does not define a portable line width or point size, JHV uses fixed values; use SunJSON when a JHV-only
product requires specific line widths or point sizes.

JHV supports static geometry only; animation, skinning, and morph targets are not supported. Lines and points may
carry material or per-vertex colors but not textures. JHV also does not support additive blending, separate opacity
textures, or transformed texture coordinates.

For triangle meshes, a `MASK` material discards fragments below its alpha cutoff. For lines and points, JHV applies
the cutoff to vertex colors before rendering; transitions along a line segment are therefore only approximate.

Simple translucent surfaces are supported, but the appearance of overlapping surfaces can depend on their drawing
order. Splitting them into separate objects allows JHV to order them independently and usually gives a more
predictable result. Opaque triangle surfaces use the depth buffer normally; a double-sided surface is visible from
either side, whereas a single-sided surface disappears when viewed from its back.

JHV does not turn arbitrary vertex attributes into selectable data channels. The producer chooses the displayed
quantity, converts it to vertex colors or a base-color texture, and records the quantity, units, range, and color map
in scene `extras`.

When accuracy allows, simplify dense lines and decimate dense triangle meshes before export. Choose and record a
geometric or data-aware error criterion, preserve important boundaries and attributes, and compare the reduced
product with the full result. Careful reduction can substantially reduce file size, transfer time, and rendering
cost.

The interface can be extended when a new product needs a feature not covered here. Each addition should include a
representative product and JHV integration tests, giving producers and JHV maintainers a concrete result to verify
together.

# Worked COCONUT glTF conversion

`extra/test/create_coconut_scene.py` is a worked demonstration of the supported glTF capabilities and a starting point
for using Qorona, PyVista/VTK, and pygltflib to produce a JHV-compatible asset from model output. It is tailored to
the supplied COCONUT demonstration CFmesh and is not intended as a general COCONUT exporter. The resulting
`coconut-corona-scene.glb` contains:

- unlit magnetic field lines colored by polarity;
- an unlit triangulated `B_r=0` current-sheet surface colored by radial plasma velocity;
- unlit point markers at the inner and outer boundary endpoints of complete open field lines; and
- eleven selected closed field lines represented by lit, thick yellow tubes with smooth vertex normals.

The tube centerlines follow field lines traced from the supplied background magnetic field. Their selection, radius,
color, and representation as solid tubes are artificial choices made only to demonstrate mesh normals and lighting.

The current sheet is unlit because its color map represents radial plasma velocity. Shading it would change the
brightness according to surface orientation, making the same velocity appear as different colors across the mesh.
The field-line and boundary-point materials are also marked as unlit because their colors identify magnetic polarity,
although JHV does not shade line or point primitives. The tubes, in contrast, have a constant illustrative color and
remain lit so that their round cross-section and three-dimensional shape are visible.

Qorona can export field lines directly as SunJSON, whereas this example uses glTF to combine lines, surfaces, and
points in one asset that general glTF viewers can also display.

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

- model coordinates are treated as Carrington-aligned Cartesian coordinates in solar radii;
- the Earth observer is calculated at the supplied solution time, and exported geometry is rotated into that
  observer's `SOLX/SOLY/SOLZ` frame;
- the native cells are reconstructed with Qorona's degree-1 moving-least-squares resampler on a logarithmic
  `192 x 180 x 360` spherical grid spanning 1 to 6 solar radii;
- field lines are traced in float64 with DOPRI5, `rtol=10^-8`, `cfl=0.125`, and an `18 x 36` seed grid; their glTF
  positions are stored as float32;
- after tracing, the field-line paths are simplified with the Ramer-Douglas-Peucker algorithm so that no removed
  trace vertex is farther than `10^-5` solar radii (about 7 km for the adopted solar radius) from the simplified
  path; the same step is applied to the centerlines used to make the demonstration tubes;
- both boundary endpoints of each complete open field line are exported as points, with the line's polarity color;
- eleven additional closed field lines are traced from equally spaced seeds at Carrington latitude 6 degrees and
  longitudes 32 through 42 degrees, then converted to 4 Mm-radius tubes with 16 sides;
- the current sheet is extracted as the `B_r=0` isosurface of the reconstructed magnetic field, matching the
  geometric definition used by Guo et al. (2024, Fig. 1d), with the periodic longitude seam closed before meshing;
- the model velocity is interpolated at each surface vertex, converted with the COOLFluiD `corona` normalization
  `v0 = 480 km/s`, and projected onto the local radial direction;
- the extracted current-sheet mesh is reduced by a target of 50 percent with scalar-aware quadric decimation, using
  radial velocity in the decimation error metric; vertex colors are generated from the resulting scalar values after
  this step; and
- radial velocity is mapped through the `turbo` color map over -30 to 300 km/s, with a common surface alpha of 0.35.

The default scene's `extras` records the source name and SHA-256 digest, Qorona version, processing parameters,
surface definition, velocity mapping, and geometry counts before and after display-geometry post-processing. These
steps reduce the product size without lowering the reconstruction grid or changing the field-line tracing tolerances.

PyVista and VTK create the line, triangle, tube, and point geometry. The script obtains VTK's glTF document in memory,
adds the solar metadata, and uses pygltflib to package it as GLB without an intermediate `.gltf` file. Colors are
normalized unsigned-byte straight RGBA values. The current sheet is double-sided and uses ordinary alpha blending.
The script marks the field-line, current-sheet, and point materials with `KHR_materials_unlit`. It leaves the tube
material lit and includes one smooth normal per tube vertex.

## Validation

The converter checks its inputs before export, then reopens the completed GLB and verifies:

- one default scene carrying the exact solar and provenance metadata;
- one line primitive, two triangle primitives, and one point primitive;
- finite float32 positions and non-degenerate adjacent line segments;
- accessor counts, index counts, normalized RGBA attributes, and expected base materials;
- the line, current-sheet, and point materials are unlit, while the yellow tube material is lit;
- a complete float32 normal vector for every tube vertex;
- a non-empty triangulated current sheet with the configured colors, alpha, and double-sided material;
- boundary points on both model boundaries with both polarity colors; and
- a single embedded binary buffer of the expected size.

JHV integration tests should cover several viewpoints, non-zero observer longitude and latitude, solar occultation,
surface winding, unlit colors, transparency, lines, points, and interaction with other depth-writing layers. The same
asset should also be opened in an independent glTF viewer to confirm that it remains portable.

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
