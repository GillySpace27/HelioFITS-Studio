---
title: JHV Heliocentric 3D scene interface
subject: Space Weather HelioViewer
date: 2026-08-31
lof: false
lot: false
---

`id: \exec{git hash-object \file}`

# Introduction

This document defines the glTF 2.0 interface for heliocentric 3D geometry in JHelioviewer (JHV). A scene may contain
field lines, triangulated surfaces, points, colors, materials, textures, and a static object hierarchy. The producer
creates and describes that geometry; JHV places and renders the supported content. JHV does not derive geometry or a
visualization from model fields. The COCONUT conversion is an example, not a requirement for other products.

## glTF and GLB

glTF has two file forms:

- `.gltf` stores the scene description as JSON and may refer to separate binary buffers and images or embed them as
  Base64 data;
- `.glb` packages the JSON and binary resources in one file.

GLB is convenient for distribution and avoids Base64 expansion. Its binary payload is about 25% smaller than the same
payload embedded as Base64. It has no size advantage over a `.gltf` file with external binary resources.

Either form may be gzip-compressed. JHV accepts `.gltf.gz` and `.glb.gz`; an HTTP server may instead use
`Content-Encoding: gzip`. Gzip compresses the complete asset for storage or transport and does not alter its content.

## Choosing between glTF and SunJSON

SunJSON remains JHV's native interchange format for solar points, polylines, and ellipses. Its coordinates are
`[radius, Carrington longitude, Carrington latitude]`, with radius in solar radii and angles in degrees. It can also
carry JHV-specific line thickness and point size. Qorona already uses SunJSON for its JHV field-line products.

Use glTF for triangle meshes, textures, or scenes that combine surfaces, lines, and points. It can also be displayed by
general glTF viewers. For field lines alone, SunJSON is usually simpler and preserves JHV-specific widths and sizes.

A glTF `POSITION` attribute always contains Cartesian `x`, `y`, and `z` components. Storing spherical tuples such as
`[radius, longitude, latitude]` in `POSITION` would make the file appear distorted in standard viewers. Producers
whose source data is spherical must convert it to Cartesian positions before export. The solar metadata below tells
JHV how to place those axes. Other viewers ignore the metadata and display the local Cartesian geometry.

# Solar coordinates and observation metadata

## Heliocentric Cartesian frame

The local Cartesian axes follow the heliocentric convention described by Thompson (2006):

- `SOLX` points toward solar west in the observer's image plane;
- `SOLY` points toward solar north in the observer's image plane;
- `SOLZ` points from Sun center toward the observer.

The origin is Sun center. This is an observer-aligned Cartesian frame, not a Carrington Cartesian frame.

JHV's current scene profile requires positions in solar radii and uses a physical solar radius of 695700000 meters.
The three glTF position components therefore have the following fixed declaration:

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

`DSUN_OBS` records the observer distance from Sun center in meters. JHV requires it but does not currently use it to
place the scene.

If any solar-coordinate field is supplied, JHV requires the complete declaration shown above and requires
`DATE-OBS`. A scene without solar metadata can still be loaded, but its coordinates are treated as JHV world
coordinates and it has no observation time.

JHV uses the observer longitude and latitude to rotate the declared `SOLX/SOLY/SOLZ` positions into Carrington
coordinates. It applies the same placement to triangles, lines, and points. Rotating the JHV view changes the camera,
not the product's coordinates.

# Supported glTF content

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

glTF colors use straight alpha. Producers must write ordinary, non-premultiplied color values; JHV converts them to
the premultiplied representation used by its renderer.

JHV applies simple directional shading to triangle surfaces unless a material is marked as *unlit*. Shading shows the
surface shape but changes the apparent brightness of its colors. Use the glTF `KHR_materials_unlit` extension when
colors encode values that must be displayed unchanged. JHV does not implement the complete glTF metallic/roughness
lighting model, normal maps, emissive materials, or lights and cameras stored in the asset.

glTF does not define a portable line width or point size, so JHV uses fixed values. Use SunJSON when a JHV-only product
requires specific line widths or point sizes.

JHV displays static geometry. It rejects animation, skinning, and morph targets. Lines and points may carry material
or per-vertex colors but not textures. Additive blending, separate opacity textures, and transformed texture
coordinates are not supported.

Simple translucent surfaces are supported, but their result can depend on drawing order when they overlap. Split
independently ordered translucent parts into separate objects. Opaque geometry uses the depth buffer normally. A
double-sided surface is visible from either side; a single-sided surface disappears when viewed from its back.

JHV does not turn arbitrary vertex attributes into selectable data channels. The producer chooses the displayed
quantity, converts it to vertex colors or a base-color texture, and records the quantity, units, range, and color map
in scene `extras`.

Future additions may include time-sequenced scenes, more material features, or explicit data channels. Each addition
should be defined with a representative product and JHV integration tests.

# Worked COCONUT conversion

`extra/test/create_coconut_scene.py` is a reference converter. Given a COCONUT CFmesh solution and its observation
time, it writes `coconut-corona-scene.glb` containing:

- magnetic field lines colored by polarity;
- a triangulated `B_r=0` current-sheet surface colored by radial plasma velocity; and
- point markers at the inner and outer boundary endpoints of complete open field lines.

Qorona can export field lines directly as SunJSON. This example uses glTF because it combines lines, a triangle
surface, and points in one scene that general glTF viewers can also display.

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

PyVista and VTK create the line, triangle, and point geometry. The script obtains VTK's glTF document in memory, adds
the solar metadata, and uses pygltflib to package it as GLB without an intermediate `.gltf` file. Colors are normalized
unsigned-byte straight RGBA values. The surface is double-sided and uses ordinary alpha blending. All three materials
use `KHR_materials_unlit`, so JHV does not shade their vertex colors.

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
same asset in an independent glTF viewer to check that it remains a valid glTF scene.

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
