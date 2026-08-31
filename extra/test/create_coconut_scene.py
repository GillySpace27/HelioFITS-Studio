#!/usr/bin/env python3
"""Convert a COCONUT CFmesh solution to the JHV glTF scene profile.

The CFmesh file does not contain an observation time or coordinate-frame declaration.  This script
requires the time and assumes Carrington-aligned Cartesian coordinates.  It writes a self-contained
GLB containing magnetic field lines, a triangulated B_r=0 current sheet, and the boundary endpoints
of open field lines, all rotated into the observer-aligned SOLX/SOLY/SOLZ frame.

The constants below specify one high-quality conversion.  They are example producer settings, not
requirements of the JHV interface.  The script reopens and validates the finished GLB.

Requires Qorona 0.4.0, PyVista/VTK, Matplotlib, and pygltflib.  Qorona installs NumPy, SciPy,
Astropy, and SunPy; Numba is optional and only accelerates Qorona operations.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import astropy.units as u
import numpy as np
import pyvista as pv
import qorona
from astropy.time import Time
from matplotlib import colormaps
from pygltflib import GLTF2
from scipy.ndimage import map_coordinates
from sunpy.coordinates.sun import B0, L0, earth_distance
from vtkmodules.vtkIOExport import vtkGLTFExporter

from qorona.field.sampled import SampledField
from qorona.io.readers.coconut.cfmesh import CFmeshReader
from qorona.render.fieldlines import polarity_colours
from qorona.resample import KnnMlsResampler, LogarithmicSpacing, SphericalGrid
from qorona.resample.grid import pad_field
from qorona.trace import lonlat_seeds, trace_field_lines

RSUN_REF = 695_700_000.0
MODEL_OUTER_RADIUS = 6.0

FIELD_N_R = 192
FIELD_N_THETA = 180
FIELD_N_PHI = 360
SEED_N_THETA = 18
SEED_N_PHI = 36
TRACE_RTOL = 1.0e-8
TRACE_CFL = 0.125
CURRENT_SHEET_OPACITY = 0.35
# COOLFluiD's "corona" normalization uses v0 = 4.8e7 cm/s (Guo et al. 2024).
CURRENT_SHEET_VELOCITY_SCALE_KM_S = 480.0
CURRENT_SHEET_VELOCITY_MIN_KM_S = -30.0
CURRENT_SHEET_VELOCITY_MAX_KM_S = 300.0
CURRENT_SHEET_COLORMAP = "turbo"


def main() -> None:
    args = arguments()
    args.output.parent.mkdir(parents=True, exist_ok=True)

    timestamp = normalized_utc_timestamp(args.timestamp)
    source_sha256 = sha256(args.input)
    observer = observer_metadata(timestamp)
    world_to_sol = observer_basis(observer["CRLN_OBS"], observer["CRLT_OBS"])
    solution = CFmeshReader().read(args.input, show_progress=True)
    resampler = KnnMlsResampler()
    field, velocity = build_field(solution, resampler)
    processing = {
        "qoronaVersion": qorona.__version__,
        "source": args.input.name,
        "sourceSha256": source_sha256,
        "sourceCellCount": int(solution.cell_centers.shape[0]),
        "resampler": "k-nearest-neighbour degree-1 moving least squares",
        "minimumNeighbors": resampler.n_neighbors,
        "referenceCellCount": resampler.reference_cell_count,
        "ridge": resampler.ridge,
        "fieldGrid": [FIELD_N_R, FIELD_N_THETA, FIELD_N_PHI],
        "fieldGridRadialSpacing": "logarithmic",
    }

    write_scene(
        field, velocity, world_to_sol, observer, timestamp, processing, args.output
    )

    print(f"Wrote and validated {args.output}")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "input", type=Path, help="COCONUT .CFmesh or .CFmesh.xz solution"
    )
    parser.add_argument(
        "--timestamp", required=True, help="solution observation time (ISO-8601 UTC)"
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("extra/test/data/coconut-corona-scene.glb"),
        help="output GLB file (default: extra/test/data/coconut-corona-scene.glb)",
    )
    return parser.parse_args()


def normalized_utc_timestamp(value: str) -> str:
    try:
        time = Time(value, scale="utc")
    except ValueError as error:
        raise ValueError(f"invalid UTC observation time {value!r}") from error
    time.precision = 3
    return time.utc.isot


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def observer_metadata(timestamp: str) -> dict[str, float]:
    time = Time(timestamp, scale="utc")
    return {
        "DSUN_OBS": float(earth_distance(time).to_value(u.m)),
        "CRLN_OBS": float(L0(time).to_value(u.deg)),
        "CRLT_OBS": float(B0(time).to_value(u.deg)),
        "RSUN_REF": RSUN_REF,
    }


def observer_basis(longitude_degrees: float, latitude_degrees: float) -> np.ndarray:
    """Return the orthonormal rotation from Carrington-aligned xyz to SOLX/SOLY/SOLZ.

    The returned rows are the solar-west, solar-north, and toward-observer unit vectors expressed
    in Carrington-aligned model coordinates.  For column vectors, ``sol = basis @ world`` and
    ``world = basis.T @ sol``.
    """
    longitude = np.deg2rad(longitude_degrees)
    latitude = np.deg2rad(latitude_degrees)
    toward_observer = np.array(
        [
            np.cos(latitude) * np.cos(longitude),
            np.cos(latitude) * np.sin(longitude),
            np.sin(latitude),
        ]
    )
    north = np.array([0.0, 0.0, 1.0]) - np.sin(latitude) * toward_observer
    north /= np.linalg.norm(north)
    west = np.cross(north, toward_observer)
    return np.stack((west, north, toward_observer))


def build_field(
    solution, resampler: KnnMlsResampler
) -> tuple[SampledField, np.ndarray]:
    grid = SphericalGrid(
        spacing=LogarithmicSpacing(inner=1.0, outer=MODEL_OUTER_RADIUS),
        n_r=FIELD_N_R,
        n_theta=FIELD_N_THETA,
        n_phi=FIELD_N_PHI,
    )
    names = ("Bx", "By", "Bz", "vx", "vy", "vz")
    missing = [name for name in names if name not in solution.variables]
    if missing:
        raise ValueError(f"COCONUT solution lacks variables: {', '.join(missing)}")
    components = resampler.resample(solution, grid, names, show_progress=True)
    magnetic_field = pad_field(
        np.stack([components[name] for name in names[:3]], axis=-1)
    )
    field = SampledField(grid, magnetic_field, solution.metadata.normalization)
    velocity = np.stack([components[name] for name in names[3:]], axis=-1)
    return field, velocity


def write_scene(
    field: SampledField,
    velocity: np.ndarray,
    world_to_sol: np.ndarray,
    observer: dict[str, float],
    timestamp: str,
    processing: dict[str, object],
    output: Path,
) -> None:
    seeds = lonlat_seeds(1.0, n_theta=SEED_N_THETA, n_phi=SEED_N_PHI)
    lines = trace_field_lines(
        field,
        seeds,
        store_path=True,
        show_progress=True,
        device="cpu",
        precision="float64",
        rtol=TRACE_RTOL,
        cfl=TRACE_CFL,
    )
    colors = polarity_colours(field, lines, 1.0, MODEL_OUTER_RADIUS)
    current_sheet = extract_current_sheet(field, velocity, world_to_sol)
    open_boundary_points = (
        lines.feet[lines.is_open].reshape(-1, 3) @ world_to_sol.T
    ).astype(np.float32)
    open_boundary_colors = np.rint(
        np.column_stack(
            (
                np.repeat(colors[lines.is_open], 2, axis=0),
                np.ones(len(open_boundary_points)),
            )
        )
        * 255
    ).astype(np.uint8)

    positions = []
    vertex_colors = []
    polylines = []
    vertex_count = 0
    for path, color, complete in zip(
        lines.paths, colors, lines.is_complete, strict=True
    ):
        if not complete:
            continue
        # Paths are row vectors, hence the transposed world-to-SOL matrix on the right.
        transformed = (np.asarray(path, dtype=np.float64) @ world_to_sol.T).astype(
            np.float32
        )
        # Remove adjacent points that become equal in float32. Assimp turns zero-length line
        # segments into points.
        transformed = transformed[
            np.concatenate(
                ([True], np.any(transformed[1:] != transformed[:-1], axis=1))
            )
        ]
        if len(transformed) < 2:
            continue
        first = vertex_count
        vertex_count += len(transformed)
        positions.append(transformed)
        rgba = np.rint(np.append(color, 1.0) * 255).astype(np.uint8)
        vertex_colors.append(np.tile(rgba, (len(transformed), 1)))
        polylines.append(
            np.concatenate(([len(transformed)], np.arange(first, vertex_count)))
        )

    if not positions:
        raise RuntimeError("field-line tracing produced no complete paths")
    position_array = np.concatenate(positions)
    color_array = np.concatenate(vertex_colors)
    polyline_array = np.concatenate(polylines)
    metadata = {
        "DATE-OBS": timestamp,
        **observer,
        "CTYPE1": "SOLX",
        "CTYPE2": "SOLY",
        "CTYPE3": "SOLZ",
        "CUNIT1": "solRad",
        "CUNIT2": "solRad",
        "CUNIT3": "solRad",
        "WCSNAME": "Heliocentric-cartesian",
        "PROCESSING": {
            **processing,
            "tracer": "DOPRI5",
            "tracerDevice": "cpu",
            "rtol": TRACE_RTOL,
            "cfl": TRACE_CFL,
            "precision": "float64",
            "seedGrid": [SEED_N_THETA, SEED_N_PHI],
            "incompletePathsDiscarded": True,
            "currentSheet": {
                "definition": "B_r=0",
                "grid": [FIELD_N_R, FIELD_N_THETA, FIELD_N_PHI],
                "meshing": "VTK flying edges",
                "velocityInterpolation": "trilinear on the spherical field grid",
                "colorQuantity": "radial velocity",
                "colorMap": CURRENT_SHEET_COLORMAP,
                "colorRangeKmPerS": [
                    CURRENT_SHEET_VELOCITY_MIN_KM_S,
                    CURRENT_SHEET_VELOCITY_MAX_KM_S,
                ],
                "modelVelocityUnitKmPerS": CURRENT_SHEET_VELOCITY_SCALE_KM_S,
                "dataRangeKmPerS": [
                    float(np.min(current_sheet.point_data["radialVelocity"])),
                    float(np.max(current_sheet.point_data["radialVelocity"])),
                ],
                "vertices": current_sheet.n_points,
                "triangles": current_sheet.n_cells,
            },
            "openFieldBoundaryPoints": {
                "definition": "inner and outer boundary endpoints of complete open field lines",
                "count": len(open_boundary_points),
                "colorQuantity": "polarity of the corresponding field line",
            },
        },
    }
    segment_count = sum(len(position) - 1 for position in positions)
    write_scene_glb(
        output,
        position_array,
        color_array,
        polyline_array,
        segment_count,
        current_sheet,
        open_boundary_points,
        open_boundary_colors,
        metadata,
    )


def extract_current_sheet(
    field: SampledField, velocity: np.ndarray, world_to_sol: np.ndarray
) -> pv.PolyData:
    grid = field.grid
    if velocity.shape != (grid.n_r, grid.n_theta, grid.n_phi, 3):
        raise ValueError(
            "velocity must contain one Cartesian vector per field-grid node"
        )
    if not np.isfinite(velocity).all():
        raise ValueError("velocity contains non-finite values")
    theta = grid.colatitudes[:, None]
    phi = grid.azimuths[None, :]
    radial_direction = np.stack(
        np.broadcast_arrays(
            np.sin(theta) * np.cos(phi),
            np.sin(theta) * np.sin(phi),
            np.cos(theta) * np.ones_like(phi),
        ),
        axis=-1,
    )
    b_radial = np.einsum(
        "rtpc,tpc->rtp", field.b_at_nodes(), radial_direction, optimize=True
    )
    if not np.isfinite(b_radial).all() or not (
        np.min(b_radial) <= 0.0 <= np.max(b_radial)
    ):
        raise RuntimeError("resampled magnetic field has no finite B_r=0 surface")

    # Close the periodic longitude axis before contouring. ImageData keeps the logical grid
    # implicit, avoiding another full Cartesian copy of the high-resolution magnetic field.
    b_radial = np.concatenate((b_radial, b_radial[:, :, :1]), axis=2).astype(np.float32)
    logical_grid = pv.ImageData(dimensions=b_radial.shape)
    logical_grid.point_data["B_r"] = b_radial.ravel(order="F")
    surface = logical_grid.contour(
        [0.0],
        scalars="B_r",
        compute_normals=False,
        compute_scalars=False,
        method="flying_edges",
    ).triangulate()
    if surface.n_points == 0 or surface.n_cells == 0:
        raise RuntimeError("B_r=0 contouring produced an empty current sheet")

    logical = np.asarray(surface.points, dtype=np.float64)
    periodic_velocity = np.concatenate((velocity, velocity[:, :, :1]), axis=2)
    surface_velocity = np.column_stack(
        [
            map_coordinates(
                periodic_velocity[..., component],
                logical.T,
                order=1,
                mode="nearest",
                prefilter=False,
            )
            for component in range(3)
        ]
    )
    radius = grid.spacing.radius(logical[:, 0] / (grid.n_r - 1))
    colatitude = (logical[:, 1] + 0.5) * (np.pi / grid.n_theta)
    azimuth = logical[:, 2] * (2.0 * np.pi / grid.n_phi)
    sin_colatitude = np.sin(colatitude)
    model_points = np.column_stack(
        (
            radius * sin_colatitude * np.cos(azimuth),
            radius * sin_colatitude * np.sin(azimuth),
            radius * np.cos(colatitude),
        )
    )
    radial_velocity = (
        np.einsum("ij,ij->i", surface_velocity, model_points / radius[:, None])
        * CURRENT_SHEET_VELOCITY_SCALE_KM_S
    )
    normalized_velocity = np.clip(
        (radial_velocity - CURRENT_SHEET_VELOCITY_MIN_KM_S)
        / (CURRENT_SHEET_VELOCITY_MAX_KM_S - CURRENT_SHEET_VELOCITY_MIN_KM_S),
        0.0,
        1.0,
    )
    rgba = colormaps[CURRENT_SHEET_COLORMAP](normalized_velocity, bytes=True)
    rgba[:, 3] = round(255 * CURRENT_SHEET_OPACITY)
    surface.points = np.asarray(model_points @ world_to_sol.T, dtype=np.float32)
    surface.point_data["RGBA"] = np.ascontiguousarray(rgba, dtype=np.uint8)
    surface.point_data["radialVelocity"] = radial_velocity
    surface = surface.clean(tolerance=1.0e-6, absolute=True)
    # Joining the coincident longitude seam can collapse a handful of seam triangles. VTK's
    # cleaner preserves those degeneracies as line or point cells; retain only the polygonal
    # faces so the exported object remains a pure triangle mesh.
    polygon_surface = pv.PolyData(surface.points, surface.faces)
    polygon_surface.point_data["RGBA"] = surface.point_data["RGBA"]
    polygon_surface.point_data["radialVelocity"] = surface.point_data["radialVelocity"]
    surface = polygon_surface.remove_unused_points()
    if not surface.is_all_triangles:
        raise RuntimeError("current-sheet contour is not a triangle mesh")
    return surface


def write_scene_glb(
    output: Path,
    positions: np.ndarray,
    colors: np.ndarray,
    polylines: np.ndarray,
    segment_count: int,
    current_sheet: pv.PolyData,
    boundary_points: np.ndarray,
    boundary_colors: np.ndarray,
    metadata: dict[str, object],
) -> None:
    if (
        positions.ndim != 2
        or positions.shape[1] != 3
        or not np.isfinite(positions).all()
    ):
        raise ValueError("positions must be a finite Nx3 array")
    if colors.shape != (len(positions), 4):
        raise ValueError("colors must contain one RGBA value per position")
    if polylines.ndim != 1 or len(polylines) == 0:
        raise ValueError("polylines must be a non-empty VTK line-cell array")
    if current_sheet.n_points == 0 or current_sheet.n_cells == 0:
        raise ValueError("current sheet must be a non-empty triangle mesh")
    if (
        boundary_points.ndim != 2
        or boundary_points.shape[1] != 3
        or len(boundary_points) == 0
        or not np.isfinite(boundary_points).all()
    ):
        raise ValueError("boundary points must be a finite, non-empty Nx3 array")
    if boundary_colors.shape != (len(boundary_points), 4):
        raise ValueError("boundary colors must contain one RGBA value per point")
    boundary_radii = np.linalg.norm(boundary_points, axis=1)
    inner_boundary = np.isclose(boundary_radii, 1.0, atol=1.0e-5)
    outer_boundary = np.isclose(boundary_radii, MODEL_OUTER_RADIUS, atol=1.0e-5)
    if not np.all(inner_boundary | outer_boundary) or not (
        np.any(inner_boundary) and np.any(outer_boundary)
    ):
        raise ValueError("boundary points must include both model boundaries")
    if (
        np.any(boundary_colors[:, 3] != 255)
        or len(np.unique(boundary_colors[:, :3], axis=0)) < 2
    ):
        raise ValueError(
            "boundary-point colors must be opaque and encode both polarities"
        )
    surface_colors = np.asarray(current_sheet.point_data.get("RGBA"))
    if surface_colors.shape != (current_sheet.n_points, 4):
        raise ValueError("current sheet must contain one RGBA value per vertex")
    if (
        np.any(surface_colors[:, 3] != round(255 * CURRENT_SHEET_OPACITY))
        or len(np.unique(surface_colors[:, :3], axis=0)) < 2
    ):
        raise ValueError(
            "current-sheet colors must vary in RGB and use the configured opacity"
        )

    mesh = pv.PolyData(
        np.ascontiguousarray(positions, dtype=np.float32), lines=polylines
    )
    mesh.point_data["RGBA"] = np.ascontiguousarray(colors, dtype=np.uint8)
    point_cloud = pv.PolyData(np.ascontiguousarray(boundary_points, dtype=np.float32))
    point_cloud.point_data["RGBA"] = np.ascontiguousarray(
        boundary_colors, dtype=np.uint8
    )
    plotter = pv.Plotter(off_screen=True)
    try:
        plotter.add_mesh(
            mesh,
            name="COCONUT magnetic field lines",
            scalars="RGBA",
            rgba=True,
            color="white",
            lighting=False,
            show_scalar_bar=False,
        )
        plotter.add_mesh(
            current_sheet,
            name="Heliospheric current sheet",
            scalars="RGBA",
            rgba=True,
            color="white",
            lighting=False,
            show_scalar_bar=False,
        )
        plotter.add_mesh(
            point_cloud,
            name="Open-field-line boundary endpoints",
            style="points",
            scalars="RGBA",
            rgba=True,
            color="white",
            lighting=False,
            show_scalar_bar=False,
        )
        # Use VTK directly because PyVista's export_gltf() only writes to a file.
        exporter = vtkGLTFExporter()
        exporter.SetRenderWindow(plotter.render_window)
        exporter.SetInlineData(True)
        exporter.SetSaveNormal(False)
        document = json.loads(exporter.WriteToString())
    finally:
        plotter.close()

    scene_index = document.get("scene", 0)
    scene = document["scenes"][scene_index]
    scene["name"] = "COCONUT corona"
    scene["extras"] = metadata

    line_meshes = []
    surface_meshes = []
    point_meshes = []
    for mesh_index, exported_mesh in enumerate(document.get("meshes", [])):
        modes = {primitive.get("mode", 4) for primitive in exported_mesh["primitives"]}
        if modes == {1}:
            line_meshes.append(mesh_index)
        elif modes == {4}:
            surface_meshes.append(mesh_index)
        elif modes == {0}:
            point_meshes.append(mesh_index)
    if len(line_meshes) != 1 or len(surface_meshes) != 1 or len(point_meshes) != 1:
        raise RuntimeError(
            "VTK did not export one line, one triangle, and one point mesh"
        )

    line_mesh = line_meshes[0]
    surface_mesh = surface_meshes[0]
    point_mesh = point_meshes[0]
    mesh_names = {
        line_mesh: "COCONUT magnetic field lines",
        surface_mesh: "Heliospheric current sheet",
        point_mesh: "Open-field-line boundary endpoints",
    }
    for mesh_index, name in mesh_names.items():
        document["meshes"][mesh_index]["name"] = name
    for node in document.get("nodes", []):
        if node.get("mesh") in mesh_names:
            node["name"] = mesh_names[node["mesh"]]

    materials = document.get("materials", [])
    unlit_material_indices = set()
    for mesh_index, name in mesh_names.items():
        for primitive in document["meshes"][mesh_index]["primitives"]:
            material_index = primitive.get("material")
            if material_index is None or not 0 <= material_index < len(materials):
                raise RuntimeError(f"VTK mesh {name} has no valid material")
            unlit_material_indices.add(material_index)

    surface_primitive = document["meshes"][surface_mesh]["primitives"][0]
    surface_material = materials[surface_primitive["material"]]
    surface_material["alphaMode"] = "BLEND"
    surface_material["doubleSided"] = True

    # lighting=False is a PyVista setting; VTK does not export it to glTF.
    extensions_used = document.setdefault("extensionsUsed", [])
    if "KHR_materials_unlit" not in extensions_used:
        extensions_used.append("KHR_materials_unlit")
    for material_index in unlit_material_indices:
        material = materials[material_index]
        material.setdefault("extensions", {})["KHR_materials_unlit"] = {}

    # Package VTK's in-memory glTF document as one GLB.
    GLTF2.gltf_from_json(json.dumps(document)).save_binary(output)

    # Reopen the file to validate the packaged GLB.
    validate_scene_glb(
        output,
        len(positions),
        segment_count,
        current_sheet.n_points,
        current_sheet.n_cells,
        len(boundary_points),
        metadata,
    )


def validate_scene_glb(
    path: Path,
    expected_vertex_count: int,
    expected_segment_count: int,
    expected_surface_vertex_count: int,
    expected_surface_triangle_count: int,
    expected_point_count: int,
    expected_metadata: dict[str, object],
) -> None:
    document = GLTF2().load(path)
    scene_index = document.scene if document.scene is not None else 0
    if (
        document.asset is None
        or document.asset.version != "2.0"
        or document.scenes is None
        or len(document.scenes) != 1
        or not 0 <= scene_index < len(document.scenes)
        or document.meshes is None
        or len(document.meshes) != 3
        or document.materials is None
        or len(document.materials) != 3
        or document.extensionsUsed is None
        or "KHR_materials_unlit" not in document.extensionsUsed
        or document.accessors is None
        or document.buffers is None
        or len(document.buffers) != 1
    ):
        raise RuntimeError("completed GLB has an unexpected scene structure")

    scene = document.scenes[scene_index]
    accessors = document.accessors
    materials = document.materials
    primitives = [
        primitive
        for mesh in document.meshes
        for primitive in (mesh.primitives if mesh.primitives is not None else [])
    ]
    line_primitives = [primitive for primitive in primitives if primitive.mode == 1]
    surface_primitives = [primitive for primitive in primitives if primitive.mode == 4]
    point_primitives = [primitive for primitive in primitives if primitive.mode == 0]
    if (
        len(primitives) != 3
        or len(line_primitives) != 1
        or len(surface_primitives) != 1
        or len(point_primitives) != 1
    ):
        raise RuntimeError(
            "completed GLB does not contain one line, one triangle, and one point primitive"
        )

    line = line_primitives[0]
    surface = surface_primitives[0]
    points = point_primitives[0]
    if (
        line.attributes is None
        or line.attributes.POSITION is None
        or line.attributes.COLOR_0 is None
        or line.indices is None
        or line.material is None
        or surface.attributes is None
        or surface.attributes.POSITION is None
        or surface.attributes.COLOR_0 is None
        or surface.indices is None
        or surface.material is None
        or points.attributes is None
        or points.attributes.POSITION is None
        or points.attributes.COLOR_0 is None
        or points.indices is None
        or points.material is None
        or any(
            index is None or not 0 <= index < len(accessors)
            for index in (
                line.attributes.POSITION,
                line.attributes.COLOR_0,
                line.indices,
                surface.attributes.POSITION,
                surface.attributes.COLOR_0,
                surface.indices,
                points.attributes.POSITION,
                points.attributes.COLOR_0,
                points.indices,
            )
        )
        or not 0 <= line.material < len(materials)
        or not 0 <= surface.material < len(materials)
        or not 0 <= points.material < len(materials)
        or any(
            materials[material_index].extensions is None
            or "KHR_materials_unlit" not in materials[material_index].extensions
            for material_index in (line.material, surface.material, points.material)
        )
        or materials[line.material].pbrMetallicRoughness is None
        or materials[surface.material].pbrMetallicRoughness is None
        or materials[points.material].pbrMetallicRoughness is None
    ):
        raise RuntimeError("completed GLB contains invalid primitive references")

    line_position = accessors[line.attributes.POSITION]
    line_color = accessors[line.attributes.COLOR_0]
    line_indices = accessors[line.indices]
    surface_position = accessors[surface.attributes.POSITION]
    surface_color = accessors[surface.attributes.COLOR_0]
    surface_indices = accessors[surface.indices]
    point_position = accessors[points.attributes.POSITION]
    point_color = accessors[points.attributes.COLOR_0]
    point_indices = accessors[points.indices]
    line_base_color = materials[line.material].pbrMetallicRoughness.baseColorFactor
    surface_material = materials[surface.material]
    surface_base_color = surface_material.pbrMetallicRoughness.baseColorFactor
    point_base_color = materials[points.material].pbrMetallicRoughness.baseColorFactor
    binary_blob = document.binary_blob()
    if (
        scene.name != "COCONUT corona"
        or scene.extras != expected_metadata
        or line_position.count != expected_vertex_count
        or line_color.count != expected_vertex_count
        or line_indices.count != 2 * expected_segment_count
        or line_color.componentType != 5121
        or line_color.type != "VEC4"
        or line_color.normalized is not True
        or line_base_color != [1.0, 1.0, 1.0, 1.0]
        or surface_position.count != expected_surface_vertex_count
        or surface_color.count != expected_surface_vertex_count
        or surface_indices.count != 3 * expected_surface_triangle_count
        or surface_color.componentType != 5121
        or surface_color.type != "VEC4"
        or surface_color.normalized is not True
        or surface_material.alphaMode != "BLEND"
        or surface_material.doubleSided is not True
        or surface_base_color != [1.0, 1.0, 1.0, 1.0]
        or point_position.count != expected_point_count
        or point_color.count != expected_point_count
        or point_indices.count != expected_point_count
        or point_color.componentType != 5121
        or point_color.type != "VEC4"
        or point_color.normalized is not True
        or point_base_color != [1.0, 1.0, 1.0, 1.0]
        or document.buffers[0].uri is not None
        or binary_blob is None
        or len(binary_blob) != document.buffers[0].byteLength
    ):
        raise RuntimeError("completed GLB does not contain the expected geometry scene")


if __name__ == "__main__":
    main()
