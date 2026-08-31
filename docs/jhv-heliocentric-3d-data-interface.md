---
title: |
   | SWHV CCN4
   | JHV Heliocentric 3D data interface
subtitle: SWHV-ROB-IF-001-CCN4 v0.9
subject: SWHV CCN4
date: SWHV-ROB-IF-001-CCN4 - Version 0.9 - 2026-08-31
lof: false
lot: false
---

# Introduction

`id: \exec{git hash-object \file}`

This document defines two interfaces for supplying heliocentric 3D geometry to JHelioviewer (JHV): SunJSON and
glTF 2.0. In both cases the producer selects the model quantities to display and converts them into geometry and
colors. JHV places and renders the resulting product together with its other solar data layers.

SunJSON is a small, JHV-specific JSON format for points, connected lines, and ellipses in Carrington spherical
coordinates, together with an observation time and JHV-specific display sizes. It was introduced for displaying
model output throughout the heliosphere and is used by Qorona for its JHV field-line products.

glTF is a general 3D asset standard for products that need triangle surfaces, textures, materials, a static object
hierarchy, or a combination of surfaces, lines, and points. Its geometry is Cartesian and can be opened in general
glTF viewers, while solar metadata added to the asset allows JHV to place it correctly.

For line-only products, SunJSON is usually the more direct route. For meshed surfaces or products intended to remain
portable outside JHV, use glTF.

# SunJSON

SunJSON was inspired by the simple geometry organization of GeoJSON, but it is a separate heliocentric format. A file
contains one timestamp and a list of geometry entries:

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

- `type`: required and exactly `SunJSON`;
- `time`: required UTC timestamp in the form `YYYY-MM-DDTHH:mm:ss`, optionally followed by fractional seconds; do
  not append `Z` or a numeric offset; and
- `geometry`: required array of geometry entries. It may contain points, lines, and ellipses in any order.

## Geometry and appearance

For a valid interchange product, every geometry entry has four fields:

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
range `0.00001` through `0.1` and uses the same field to control point size for a `point` entry. Because the exact
screen mapping is renderer-specific, producers should use representative files to agree suitable values with JHV
rather than assign physical meaning to this number.

## Loading and time selection

JHV loads SunJSON from local files and HTTP(S) URIs. Dragging a `.json` file or URI into JHV selects the SunJSON
loader; a SAMP client can instead send `jhv.load.sunjson` with either a `url` parameter or a `value` parameter
containing the complete JSON text. The data is stored in the Connection layer and drawn only while that layer is
enabled.

Each file represents one timestamp. When several files are loaded, JHV keeps them as a time sequence and displays the
one whose timestamp is nearest to the current JHV time.

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

Either form may be gzip-compressed without altering its content. JHV accepts `.gltf.gz` and `.glb.gz`; an HTTP server
may instead use `Content-Encoding: gzip` while transferring the unmodified asset.

## Heliocentric Cartesian coordinates

A glTF `POSITION` attribute always contains Cartesian `x`, `y`, and `z` components, so storing spherical tuples such
as `[radius, longitude, latitude]` there would distort the asset in standard viewers. Producers whose source data is
spherical must convert it to Cartesian positions before export.

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

`DATE-OBS` is the observation or model-state time. `CRLN_OBS` and `CRLT_OBS` are the Carrington longitude and latitude
of the observer in degrees; latitude must be between -90 and 90 degrees. The producing software should calculate both
coordinates for the product time rather than substitute a nominal `(0, 0)` observer.

`DSUN_OBS` records the observer distance from Sun center in meters; JHV requires it but does not currently use it to
place the geometry.

If any solar-coordinate field is supplied, JHV requires the complete declaration shown above and requires
`DATE-OBS`. An asset without solar metadata can still be loaded, but its coordinates are treated as JHV world
coordinates and it has no observation time.

JHV uses the observer longitude and latitude to rotate the declared `SOLX/SOLY/SOLZ` positions into Carrington
coordinates, applying the same placement to triangles, lines, and points. Rotating the JHV view changes the camera
rather than the product's coordinates, while other glTF viewers ignore the solar metadata and display the local
Cartesian geometry.

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
- materials whose colors are displayed without lighting or shading.

glTF colors use straight alpha, so producers must write ordinary, non-premultiplied values; JHV converts them to the
premultiplied representation used by its renderer.

JHV applies simple directional shading to triangle surfaces unless a material is marked as *unlit*. This shading
shows the surface shape but changes the apparent brightness of its colors, so use the glTF `KHR_materials_unlit`
extension when encoded values must be displayed unchanged. JHV does not implement the complete glTF
metallic/roughness lighting model, normal maps, emissive materials, or lights and cameras stored in the asset.

Because glTF does not define a portable line width or point size, JHV uses fixed values; use SunJSON when a JHV-only
product requires specific line widths or point sizes.

JHV displays static geometry and rejects animation, skinning, and morph targets. Lines and points may carry material
or per-vertex colors but not textures, while additive blending, separate opacity textures, and transformed texture
coordinates are not supported.

Simple translucent surfaces are supported, but overlapping surfaces can depend on drawing order and should be split
into independently ordered objects when necessary. Opaque geometry uses the depth buffer normally; a double-sided
surface is visible from either side, whereas a single-sided surface disappears when viewed from its back.

JHV does not turn arbitrary vertex attributes into selectable data channels. The producer chooses the displayed
quantity, converts it to vertex colors or a base-color texture, and records the quantity, units, range, and color map
in scene `extras`.

The interface can grow as concrete products require more. A representative product and JHV integration tests should
accompany each addition so that producers and JHV maintainers can verify the same behavior.

# Worked COCONUT glTF conversion

`extra/test/create_coconut_scene.py` is a reference converter. Given a COCONUT CFmesh solution and its observation
time, it writes `coconut-corona-scene.glb` containing:

- magnetic field lines colored by polarity;
- a triangulated `B_r=0` current-sheet surface colored by radial plasma velocity; and
- point markers at the inner and outer boundary endpoints of complete open field lines.

Qorona can export field lines directly as SunJSON, whereas this example uses glTF to combine lines, a triangle
surface, and points in one asset that general glTF viewers can also display.

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
- both boundary endpoints of each complete open field line are exported as points, with the line's polarity color;
- the current sheet is extracted as the `B_r=0` isosurface of the reconstructed magnetic field, matching the
  geometric definition used by Guo et al. (2024, Fig. 1d), with the periodic longitude seam closed before meshing;
- the model velocity is interpolated at each surface vertex, converted with the COOLFluiD `corona` normalization
  `v0 = 480 km/s`, and projected onto the local radial direction; and
- radial velocity is mapped through the `turbo` color map over -30 to 300 km/s, with a common surface alpha of 0.35.

The default scene's `extras` records the source name and SHA-256 digest, Qorona version, processing parameters,
surface definition, velocity mapping, and geometry counts.

\newpage

PyVista and VTK create the line, triangle, and point geometry. The script obtains VTK's glTF document in memory, adds
the solar metadata, and uses pygltflib to package it as GLB without an intermediate `.gltf` file. Colors are normalized
unsigned-byte straight RGBA values; the surface is double-sided and uses ordinary alpha blending, while all three
materials use `KHR_materials_unlit` so that JHV does not shade their vertex colors.

## Validation

The converter validates semantic inputs before export and then reopens the completed GLB. It checks:

- one default scene carrying the exact solar and provenance metadata;
- one line, one triangle, and one point primitive;
- finite float32 positions and non-degenerate adjacent line segments;
- accessor counts, index counts, normalized RGBA attributes, and white base materials;
- all three materials are marked as unlit (`KHR_materials_unlit`);
- a non-empty triangulated current sheet with the configured colors, alpha, and double-sided material;
- boundary points on both model boundaries with both polarity colors; and
- a single embedded binary buffer of the expected size.

JHV integration tests should cover several viewpoints, non-zero observer longitude and latitude, solar occultation,
surface winding, unlit colors, transparency, lines, points, and interaction with other depth-writing layers. Open the
same asset in an independent glTF viewer to check that it remains a valid glTF asset.

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
