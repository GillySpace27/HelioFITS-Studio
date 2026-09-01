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

A later example uses COCONUT output to demonstrate the conversion process, but the two formats can also carry
heliocentric products derived from other models.

For products made only from points, lines, and ellipses, SunJSON is usually the more direct route. For meshed surfaces
or products intended to remain portable outside JHV, use glTF.

# SunJSON

The SunJSON format definition presented here was extracted from older project documents to consolidate JHV's full
heliocentric 3D data interface in one document.

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

Colors are specified as straight, non-premultiplied RGBA values, with integer components from 0 through 255. JHV clamps
values outside that range and converts the result to its premultiplied representation. When fewer colors than
coordinates are supplied, the last color is repeated. Extra colors are ignored, and an ellipse uses only its first
color. The `colors` field must be present, although an empty array may be used to select JHV's default green.

`thickness` is a JHV display parameter, not a physical width measured in solar radii. For a `point` entry, it controls
point size instead of line thickness. Its mapping to screen width or point size is implementation-dependent, so
producers should check the resulting appearance in JHV.

## Loading and time selection

JHV loads SunJSON from local files and HTTP URIs. Dragging a local `.json` file or an HTTP URI ending in
`.json` into JHV selects the SunJSON loader. A SAMP client can instead send `jhv.load.sunjson` with either a `url`
parameter or a `value` parameter containing the complete JSON text. The data is loaded into the Connection layer and
drawn only when that layer is enabled.

Each file represents one timestamp. When several files are loaded, JHV treats them as a time sequence and displays
the file whose timestamp is nearest to the current JHV time.

SunJSON deliberately has a narrow scope: it has no triangle surfaces, textures, materials, object hierarchy, or
portable definition of its display sizes. Use glTF when a product needs those features.

# glTF

glTF 2.0 is an open standard maintained by Khronos for exchanging 3D assets. A glTF asset can combine geometry,
colors, materials, textures, and a hierarchy of objects in a form understood by many 3D tools and viewers.

JHV supports the subset defined in this section. Heliocentric products add scene metadata so that JHV can
orient and place their Cartesian geometry in the Carrington frame.

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

A glTF `POSITION` attribute always contains Cartesian `x`, `y`, and `z` components. Standard viewers would interpret
spherical tuples such as `[radius, longitude, latitude]` as Cartesian coordinates and place the vertices incorrectly.
Producers whose source data is spherical must convert it to Cartesian positions before export.

The Cartesian axes used by the product follow the heliocentric convention described by Thompson (2006):

- `SOLX` points toward solar west in the observer's image plane;
- `SOLY` points toward solar north in the observer's image plane;
- `SOLZ` points from Sun center toward the observer.

The coordinate origin is the center of the Sun. In the scene metadata, `CRLN_OBS` and `CRLT_OBS` give the Carrington
direction of the `SOLZ` axis. For a physical observation this is the direction of the observer, while a model product
that is not tied to a viewpoint may use a reference direction matching its native axes. At
`CRLN_OBS = 0` and `CRLT_OBS = 0`, `SOLZ` points toward Carrington longitude zero in the solar equatorial plane,
`SOLX` points toward Carrington longitude 90 degrees, and `SOLY` points north. Other products may declare a non-zero
direction, which JHV uses to rotate the product's Cartesian coordinates into its Carrington world frame.

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

`CRLN_OBS` and `CRLT_OBS` give, in degrees, the Carrington longitude and latitude of the `SOLZ` direction at
`DATE-OBS`. Together they define the orientation of all three Cartesian axes. They may describe a physical observer
or a reference direction chosen for a model product. In either case, the positions must be expressed in the
corresponding `SOLX`, `SOLY`, and `SOLZ` frame. Latitude must be between -90 and 90 degrees.

`DSUN_OBS` gives the distance of the observer or reference point from Sun center in meters. A model product using only
a reference direction may omit it or supply a conventional positive distance. JHV validates the value when it is
present but does not currently use it to place the geometry.

A file either supplies the complete JHV scene metadata or none of it. The presence of `WCSNAME` identifies the
declaration to JHV. JHV then requires and validates `DATE-OBS`, `CTYPE1` through `CTYPE3`, `CUNIT1` through `CUNIT3`,
`RSUN_REF`, `CRLN_OBS`, and `CRLT_OBS`, together with optional `DSUN_OBS`. Without `WCSNAME`, an ordinary glTF asset
instead receives default metadata: JHV treats its positions as world coordinates and assigns the application-start
time, as it does for an image without metadata. This fallback lies outside the heliocentric interface defined here.

JHV applies this placement to triangles, lines, and points. Rotating the JHV view changes the camera, not the
product's coordinates. Other glTF viewers do not interpret the solar metadata and display the Cartesian geometry
without Carrington placement.

## Supported glTF content

JHV uses Assimp, the Open Asset Import Library, to read both glTF file forms. glTF includes features that JHV does not
support, so the following paragraphs define the supported subset.

**Geometry and scene structure.** JHV renders open or closed triangle surfaces, connected lines and polylines, and
point sets. It applies the translations, rotations, and scales from the static node hierarchy before placing the
resulting geometry in the solar scene, including when a mesh is referenced by more than one node. Animations, skins,
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

**Lighting and surfaces.** The Khronos glTF extension `KHR_materials_unlit` tells renderers to display a material
without lighting. JHV follows this rule for triangle materials. Without the extension, it lights them and requires
vertex normals. JHV uses a simple viewer-facing light: a surface facing the viewer retains its full color, while
surfaces angled away become darker but retain 30 percent ambient brightness. The light follows the view as the scene
is rotated. Because shading reveals surface shape by changing the apparent brightness of its colors, materials whose
colors encode values that must remain unchanged should be marked as unlit.

Lit triangle meshes must include vertex normals that describe the intended surface. JHV does not generate missing
normals because doing so could smooth edges that were meant to remain sharp. Unlit triangle meshes do not need
normals, and JHV discards them if they are present. Omitting them from an unlit product also reduces its file size and
memory use. JHV does not implement the complete glTF metallic/roughness model, normal maps, emissive materials, or
lights and cameras stored in the asset.

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
reduced product with the full result helps determine whether it preserves the necessary geometry and attributes.
Careful geometry reduction can substantially decrease file size, transfer time, and rendering cost.

Before distributing a product, inspect it from several viewpoints in JHV, including its interaction with the solar
sphere and other layers, and open the same asset in an independent glTF viewer. These checks can reveal placement,
surface orientation, transparency, and portability problems that structural validation alone cannot find.

This profile covers the products considered so far. If new real-world products need other ways to represent or
display their data, JHV's glTF support can be expanded where feasible.

## COCONUT example

### Overview

`extra/test/create_coconut_scene.py` shows how Qorona, PyVista/VTK, and pygltflib can be combined to produce a glTF
asset using the features JHV supports. It is a starting point for model-specific converters, not a general COCONUT
exporter, and is tailored to the supplied COCONUT sample CFmesh. The resulting `coconut-corona-scene.glb` contains:

- unlit magnetic field lines colored by polarity;
- an unlit triangulated `B_r=0` current-sheet surface colored by radial plasma velocity;
- unlit point markers at the inner and outer boundary endpoints of complete open field lines; and
- eleven selected closed field lines represented by lit, thick yellow tubes with smooth vertex normals.

The tube centerlines follow field lines traced from the supplied background magnetic field. Their selection, radius,
color, and representation as solid tubes are artificial choices included to show mesh normals and lighting. They
demonstrate a construction that could be adapted to visualize model-derived flux ropes.

The current sheet is unlit because its color map represents radial plasma velocity. Shading it would change the
brightness according to surface orientation, making the same velocity appear as different colors across the mesh.
The field-line and boundary-point materials are also marked as unlit so that general glTF viewers preserve their
polarity colors. JHV does not shade line or point primitives in either case. The tubes, in contrast, have a constant
illustrative color and remain lit so that their round cross-section and three-dimensional shape are visible.

Qorona can already export field lines as SunJSON. This example uses glTF to combine lines, surfaces, and points in one
asset that general glTF viewers can also display.

### Running the converter

Run the converter from the repository root in an environment containing Qorona 0.4.0, PyVista/VTK, Matplotlib, and
pygltflib:

```shell
python extra/test/create_coconut_scene.py \
    /path/to/coconut_corona.CFmesh.xz \
    --timestamp 2025-10-09T18:19:52 \
    --output extra/test/data/coconut-corona-scene.glb
```

### Assumptions and processing

The CFmesh file does not identify its observation time or coordinate frame. For a reproducible conversion, the script
uses the following assumptions and settings:

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
  `v0 = 480 km/s`, and projected onto the radial direction at that vertex;
- the extracted current-sheet mesh undergoes scalar-aware quadric decimation with a target reduction of 50 percent,
  using radial velocity in the decimation error metric. Vertex colors are generated from the resulting scalar values
  after this step; and
- radial velocity is mapped through the `turbo` color map over -30 to 300 km/s, with a common surface alpha of 0.35.

### Output and validation

The default scene's `extras` object records the source name and SHA-256 digest, Qorona version, processing parameters,
surface definition, velocity mapping, and geometry counts before and after polyline simplification and current-sheet
decimation. These operations reduce file size without reducing the reconstruction-grid resolution or changing the
field-line tracing tolerances.

The script uses PyVista and VTK to create the geometry and obtain a glTF document in memory. It adds the solar metadata
and uses pygltflib to package the document directly as GLB. Colors are stored as straight RGBA values in normalized
unsigned-byte attributes, and the current sheet is double-sided and alpha-blended. The field-line, current-sheet, and
point materials are marked with `KHR_materials_unlit`, while the tube material remains lit and includes one smooth
normal per vertex.

The converter checks its input arrays before export, then reopens the completed GLB and verifies its structure and
content. Structural checks cover the default scene and exact metadata, the single embedded binary buffer, the expected
line, triangle, and point primitives, and their accessor and index counts. Data checks confirm finite float32
positions, non-degenerate adjacent line segments, normalized RGBA attributes, the intended lit and unlit materials,
one normal for every tube vertex, the current sheet's colors, alpha, and double-sided material, and boundary points at
both model limits in both polarity colors.

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

# Changelog

The edition shared on 19 August 2026 covered SunJSON, an initial glTF profile, and an experimental FITS volume
proposal. This revision retains the two geometry interfaces, defines them more precisely, and withdraws the volume
proposal because it did not yet specify an unambiguous scientific visualization. The table records every change that
affects a producer or receiver, followed by changes to the COCONUT demonstration used to exercise the interface.

+-----------------------------+------------------------------------------------------------------------------------------+
| Area                        | What changed and why                                                                     |
+=============================+==========================================================================================+
| Document status             | The working note has become a versioned interface document, separating supported         |
|                             | interchange behavior from the experimental implementation work that led to it.           |
+-----------------------------+------------------------------------------------------------------------------------------+
| Scope                       | The experimental FITS scalar-volume profile has been removed. Its appearance depended on |
|                             | unresolved choices about scaling, clipping, opacity, and rendering, so a producer could  |
|                             | not determine unambiguously what JHV would display. Volume support is set aside until a  |
|                             | scientifically meaningful visualization contract can be defined.                         |
+-----------------------------+------------------------------------------------------------------------------------------+
| Qorona context              | The detailed account of Qorona's existing 2D FITS products has been removed with the     |
|                             | volume proposal. Those products remain valid, but this document is limited to            |
|                             | heliocentric 3D geometry and shows how Qorona's field-line output can use the interface. |
+-----------------------------+------------------------------------------------------------------------------------------+
| SunJSON specification       | SunJSON is fully specified instead of being mentioned only as an existing Qorona export. |
|                             | Because JHV already supports it, including the format here gives producers one document  |
|                             | for both supported heliocentric geometry interfaces.                                     |
+-----------------------------+------------------------------------------------------------------------------------------+
| Choice of format            | The roles of SunJSON and glTF are stated directly. SunJSON suits JHV-oriented points,    |
|                             | lines, and ellipses whose display size matters. glTF suits triangle surfaces, materials, |
|                             | textures, mixed geometry, and assets intended for other 3D viewers.                      |
+-----------------------------+------------------------------------------------------------------------------------------+
| SunJSON timestamp           | The required `time` field allows optional fractional seconds. JHV assumes UTC and does   |
|                             | not accept timezone designators or numeric offsets. This gives each file an unambiguous  |
|                             | position in a model time sequence.                                                       |
+-----------------------------+------------------------------------------------------------------------------------------+
| SunJSON geometry            | The document specifies coordinate order and units, minimum coordinate counts,            |
|                             | connected-line construction, and the three-point definition of an ellipse. Coordinates   |
|                             | below one solar radius are accepted with a warning, so producers can predict how JHV     |
|                             | treats them.                                                                             |
+-----------------------------+------------------------------------------------------------------------------------------+
| SunJSON colors              | Straight integer RGBA input, clamping, reuse of the last color, ignored surplus colors,  |
|                             | the first-color rule for ellipses, and the JHV green default for an empty array are all  |
|                             | defined. The result is unambiguous even when the color and coordinate counts differ.     |
+-----------------------------+------------------------------------------------------------------------------------------+
| SunJSON display size        | The permitted thickness range and its use as point size are specified. Thickness is a    |
|                             | renderer-specific display value, not a physical width, and should be chosen by checking  |
|                             | whether the resulting rendering is suitable in JHV.                                      |
+-----------------------------+------------------------------------------------------------------------------------------+
| SunJSON loading             | Local file, HTTP, and SAMP loading are documented. SunJSON is held by the Connection     |
|                             | layer and is drawn only while that layer is enabled.                                     |
+-----------------------------+------------------------------------------------------------------------------------------+
| SunJSON time sequence       | When several SunJSON files are loaded, JHV treats them as a sequence and displays the    |
|                             | file whose timestamp is nearest to the current JHV time.                                 |
+-----------------------------+------------------------------------------------------------------------------------------+
| glTF and GLB                | The document introduces `.gltf` and `.glb` as two forms of the same standard. GLB keeps  |
|                             | JSON and binary resources in one file and avoids Base64 expansion, while glTF may keep   |
|                             | binary resources external. Producers can therefore choose packaging without treating the |
|                             | forms as different scene formats.                                                        |
+-----------------------------+------------------------------------------------------------------------------------------+
| Compressed glTF             | JHV accepts `.gltf.gz`, `.glb.gz`, and HTTP `Content-Encoding: gzip`. Compression        |
|                             | reduces storage and transfer size, but not decoded memory use or rendering work, so      |
|                             | careful geometry reduction remains useful.                                               |
+-----------------------------+------------------------------------------------------------------------------------------+
| glTF coordinates            | The profile requires Cartesian `POSITION` values. Spherical `[radius, longitude,         |
|                             | latitude]` tuples must be converted before export because standard glTF viewers          |
|                             | interpret positions as Cartesian coordinates.                                            |
+-----------------------------+------------------------------------------------------------------------------------------+
| Axes and scale              | The profile fixes the axes as `SOLX`, `SOLY`, and `SOLZ`, the unit as solar radii,       |
|                             | `RSUN_REF` as `695700000.0`, and `WCSNAME` as `Heliocentric-cartesian`. Removing the     |
|                             | former choice of units makes scenes easier to validate and combine.                      |
+-----------------------------+------------------------------------------------------------------------------------------+
| Reference direction         | `CRLN_OBS` and `CRLT_OBS` give the Carrington longitude and latitude of local `SOLZ`.    |
|                             | They may describe a physical observer or simply the reference direction that aligns the  |
|                             | asset's local axes with the model, without imposing an observer interpretation on        |
|                             | viewpoint-independent output.                                                            |
+-----------------------------+------------------------------------------------------------------------------------------+
| glTF timestamp              | Every conforming heliocentric glTF product requires `DATE-OBS` with the same syntax as   |
|                             | SunJSON. JHV assumes UTC and does not accept timezone designators or numeric offsets.    |
|                             | `DATE-AVG`, underscore aliases, and precedence rules are not part of this new-product    |
|                             | interface.                                                                               |
+-----------------------------+------------------------------------------------------------------------------------------+
| Solar metadata              | The heliocentric declaration has one Carrington form based on `DATE-OBS`, `CRLN_OBS`,    |
|                             | `CRLT_OBS`, `CTYPE1..3`, `CUNIT1..3`, `RSUN_REF`, and `WCSNAME`. Stonyhurst alternatives |
|                             | and legacy FITS spelling variants are unnecessary because producers can emit this form   |
|                             | directly.                                                                                |
+-----------------------------+------------------------------------------------------------------------------------------+
| Reference distance          | `DSUN_OBS` is optional. It may describe a physical observer or conventional reference    |
|                             | point, but JHV needs only the reference direction to place Sun-centered geometry, so it  |
|                             | validates the distance without using it.                                                 |
+-----------------------------+------------------------------------------------------------------------------------------+
| Complete metadata           | An asset supplies either the complete JHV heliocentric declaration or none of it.        |
|                             | Partial metadata is rejected rather than allowing an apparently meaningful but ambiguous |
|                             | time, scale, or orientation.                                                             |
+-----------------------------+------------------------------------------------------------------------------------------+
| Ordinary glTF fallback      | A glTF asset without `WCSNAME` still opens with default world placement and the          |
|                             | application-start time. This convenience behavior is distinct from the heliocentric      |
|                             | interface, whose complete declaration is mandatory.                                      |
+-----------------------------+------------------------------------------------------------------------------------------+
| Scene geometry              | JHV supports static triangle surfaces, connected lines and polylines, and point sets. It |
|                             | applies node translations, rotations, scales, and repeated mesh references before        |
|                             | placing the scene in the solar coordinate system.                                        |
+-----------------------------+------------------------------------------------------------------------------------------+
| Line and point sizes        | glTF provides no portable line width or point size, so JHV assigns fixed values. SunJSON |
|                             | remains preferable when JHV-specific display size is part of the product.                |
+-----------------------------+------------------------------------------------------------------------------------------+
| Colors and textures         | All primitive types may combine a material base color with straight RGBA vertex colors.  |
|                             | Triangle materials may additionally use one base-color texture. Textures on lines and    |
|                             | points are not supported.                                                                |
+-----------------------------+------------------------------------------------------------------------------------------+
| Alpha modes                 | Support for `OPAQUE`, `MASK`, and `BLEND` is defined more precisely. `MASK` is evaluated |
|                             | per fragment on triangles, but for lines and points it is applied to vertex colors       |
|                             | before interpolation, making transitions along a line approximate.                       |
+-----------------------------+------------------------------------------------------------------------------------------+
| Lighting                    | `KHR_materials_unlit` selects unlit triangle materials. Other triangle materials receive |
|                             | JHV's simple viewer-facing lighting with 30 percent ambient brightness, which reveals    |
|                             | solid form while allowing producers to keep scientific colors independent of orientation |
|                             | by choosing unlit materials.                                                             |
+-----------------------------+------------------------------------------------------------------------------------------+
| Normals                     | Lit triangle meshes must provide normals that preserve their intended smooth and sharp   |
|                             | regions. JHV does not generate them because doing so could alter the intended surface.   |
|                             | Normals are unnecessary and discarded for unlit meshes, saving file and memory space.    |
+-----------------------------+------------------------------------------------------------------------------------------+
| Unsupported glTF            | The documented limits now cover animation, skins, morph targets, physically based        |
|                             | material properties beyond base color, normal maps, emissive materials, asset cameras    |
|                             | and lights, additive blending, separate opacity textures, and transformed texture        |
|                             | coordinates. Producers can therefore see the supported subset in one place.              |
+-----------------------------+------------------------------------------------------------------------------------------+
| Sidedness and depth         | Single- and double-sided surfaces and normal depth-buffer behavior are explained so      |
|                             | producers can choose whether a surface remains visible from the back and understand how  |
|                             | it interacts with the solar sphere and other JHV layers.                                 |
+-----------------------------+------------------------------------------------------------------------------------------+
| Transparency                | JHV sorts translucent mesh primitives by their centers rather than sorting individual    |
|                             | triangles. Spatially separate translucent components should therefore be exported as     |
|                             | separate mesh primitives when practical. Intersecting or self-overlapping translucent    |
|                             | surfaces can still depend on draw order.                                                 |
+-----------------------------+------------------------------------------------------------------------------------------+
| Scientific colors           | The producer selects the quantity to display, maps it to vertex colors or a base-color   |
|                             | texture, and records the quantity, units, range, and color map in scene extras. JHV does |
|                             | not turn arbitrary glTF vertex attributes into selectable scientific channels.           |
+-----------------------------+------------------------------------------------------------------------------------------+
| Product inspection          | The document recommends viewing a product from several directions in JHV, including its  |
|                             | interaction with the solar sphere and other layers, and opening it in an independent     |
|                             | glTF viewer. These checks can reveal placement, orientation, transparency, and           |
|                             | portability problems that structural validation misses.                                  |
+-----------------------------+------------------------------------------------------------------------------------------+
| Future extensions           | The former list of speculative features has been removed. When real products need        |
|                             | capabilities beyond this profile, JHV's glTF support may be extended where doing so is   |
|                             | feasible.                                                                                |
+-----------------------------+------------------------------------------------------------------------------------------+
| COCONUT tool                | The scene-only `create_coconut_scene.py` replaces `create_coconut_samples.py`, which     |
|                             | also produced a FITS volume. The script is a capability demonstration and a starting     |
|                             | point for using Qorona, PyVista/VTK, Matplotlib, and pygltflib, not a general COCONUT    |
|                             | exporter.                                                                                |
+-----------------------------+------------------------------------------------------------------------------------------+
| COCONUT output              | The demonstration produces one scene containing polarity-colored field lines, a          |
|                             | velocity-colored current sheet, open-field boundary points, and lit yellow tubes. It     |
|                             | replaces the earlier combination of a scene and an experimental FITS volume.             |
+-----------------------------+------------------------------------------------------------------------------------------+
| COCONUT orientation         | The demonstration uses `CRLN_OBS = 0` and `CRLT_OBS = 0`, with `(SOLX, SOLY, SOLZ) = (y, |
|                             | z, x)`, because the supplied COCONUT coordinates are treated as Carrington-aligned. The  |
|                             | previous Earth-based rotation was unnecessary. Other products may declare a non-zero     |
|                             | reference direction when required by their native axes.                                  |
+-----------------------------+------------------------------------------------------------------------------------------+
| Reconstruction and tracing  | The recorded quality settings include a `192 x 180 x 360` logarithmic spherical          |
|                             | reconstruction grid from 1 to 6 solar radii, degree-1 moving-least-squares               |
|                             | reconstruction, an `18 x 36` seed grid, and float64 DOPRI5 tracing tolerances. Recording |
|                             | them makes the demonstration reproducible.                                               |
+-----------------------------+------------------------------------------------------------------------------------------+
| Line reduction              | Traced paths and tube centerlines are simplified afterward with a Ramer-Douglas-Peucker  |
|                             | tolerance of `10^-5` solar radii. This bounds the introduced geometric deviation without |
|                             | changing the field-line tracing calculation.                                             |
+-----------------------------+------------------------------------------------------------------------------------------+
| Boundary points             | The demonstration and its validation retain both boundary endpoints of every complete    |
|                             | open field line, using the corresponding polarity color. The point primitive therefore   |
|                             | represents extracted model geometry rather than an arbitrary marker test.                |
+-----------------------------+------------------------------------------------------------------------------------------+
| Current sheet               | The `B_r = 0` current-sheet surface is described more precisely, including closure of    |
|                             | the periodic longitude seam and its relation to the geometric definition used by Guo et  |
|                             | al. This identifies what the surface represents instead of presenting it merely as a     |
|                             | sample mesh.                                                                             |
+-----------------------------+------------------------------------------------------------------------------------------+
| Velocity colors             | For each current-sheet vertex, the script interpolates model velocity, applies the       |
|                             | COOLFluiD `corona` normalization, projects the result radially, and maps -30 to 300 km/s |
|                             | through `turbo` with alpha 0.35. The displayed quantity and mapping are therefore        |
|                             | reproducible.                                                                            |
+-----------------------------+------------------------------------------------------------------------------------------+
| Illustrative tubes          | Eleven selected closed field lines become 4 Mm-radius, 16-sided tubes with smooth        |
|                             | normals. Their selection and solid representation are explicitly artificial. They        |
|                             | demonstrate lighting and show how traced paths can be turned into tubes for possible     |
|                             | model-derived flux-rope visualizations.                                                  |
+-----------------------------+------------------------------------------------------------------------------------------+
| Material choices            | Field lines, points, and the current sheet are unlit because their colors encode         |
|                             | polarity or velocity. The constant-color tubes are lit to reveal their round shape. This |
|                             | mixed treatment preserves quantitative colors while making the tube geometry easier to   |
|                             | see.                                                                                     |
+-----------------------------+------------------------------------------------------------------------------------------+
| Surface decimation          | Before colors are generated, the current sheet receives a target reduction of 50 percent |
|                             | using scalar-aware quadric decimation. Treating this as a separate post-processing step  |
|                             | demonstrates geometry reduction that includes radial velocity in its error criterion.    |
+-----------------------------+------------------------------------------------------------------------------------------+
| Scene provenance            | Scene extras record the source name and digest, Qorona version, processing parameters,   |
|                             | surface definition, velocity mapping, and geometry counts before and after reduction. A  |
|                             | receiver can use this information to identify and reproduce the conversion.              |
+-----------------------------+------------------------------------------------------------------------------------------+
| GLB construction            | PyVista and VTK first build an in-memory glTF document. The script then adds solar       |
|                             | metadata and uses pygltflib to package it directly as GLB. It also supplies normalized   |
|                             | unsigned-byte straight RGBA colors, a double-sided blended current sheet, and the        |
|                             | intended lit and unlit materials.                                                        |
+-----------------------------+------------------------------------------------------------------------------------------+
| Validation                  | The script validates its input arrays, then reopens the completed GLB and checks its     |
|                             | metadata, embedded buffer, primitive modes, accessor and index counts, positions, line   |
|                             | segments, RGBA values, lighting modes, tube normals, current-sheet appearance, and       |
|                             | boundary points. Validation therefore covers the delivered asset as well as the exporter |
|                             | inputs.                                                                                  |
+-----------------------------+------------------------------------------------------------------------------------------+
| References                  | References associated only with the FITS volume proposal have been removed. The          |
|                             | remaining sources cover heliocentric coordinates, Qorona field lines and SunJSON, glTF,  |
|                             | Assimp, VTK, pygltflib, and the COCONUT current-sheet and flux-rope context.             |
+-----------------------------+------------------------------------------------------------------------------------------+
