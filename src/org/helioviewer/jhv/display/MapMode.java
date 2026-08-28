package org.helioviewer.jhv.display;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.opengl.GLSLSolarShader;

/**
 * The projections the viewer can draw a scene in.
 *
 * <p>Orthographic renders directly in 3D, while the flat modes project through an explicit map
 * basis shared by rendering and mouse unprojection.
 *
 * <p><b>On the names.</b> Helioradial and Helioradial Unrolled are one projection in two layouts:
 * the same radial map, drawn Sun-centred or with position angle laid along a screen axis. They are
 * named for the coordinates they are built on, helioprojective-radial in the sense of Thompson
 * (2006), rather than for the transform that stretches the radial axis. That distinction is
 * deliberate and worth keeping: the stretch is a one-parameter family, and at lambda = 1 it is the
 * exact identity, so a name carrying the warp would be false at one end of its own slider. The
 * exponent is a parameter, the projection is not. "Warp" survives in this codebase only as the
 * verb for the transform (GLSLWarp, warpCommon.vert, warpLambda), which is the one place it was
 * ever accurate.
 */
public enum MapMode {
    // Menu order, and it carries meaning: Orthographic, HPC and Helioradial at lambda = 1 are
    // the same view at default settings and differ only in their grids, so they sit together.
    // Latitudinal is the odd one out, a surface map rather than a sky view, and goes last.
    Orthographic(GLSLSolarShader.ortho, "Orthographic"),
    HPC(GLSLSolarShader.hpc, "HPC"),
    Helioradial(GLSLSolarShader.warpSurface, "Helioradial"),
    HelioradialUnrolled(GLSLSolarShader.rectWarp, "Helioradial Unrolled"),
    Latitudinal(GLSLSolarShader.lati, "Latitudinal");

    private final GLSLSolarShader shader3D;
    private final String label;

    /**
     * The shader for this mode as currently configured.
     *
     * <p>Helioradial has two implementations. Flat, it is a fragment-space inverse map on a
     * full-screen quad (solarRadialWarp.frag), which is the original and the one the published
     * figures come from. In 3D it is a surface mesh (warpSurface). They are not
     * interchangeable: the mesh shader expects a rotated MVP and a per-vertex world position,
     * so the render path and the shader have to be switched together.
     */
    public GLSLSolarShader shader() {
        return this == Helioradial && !Display.isHelioradial3D()
                ? GLSLSolarShader.radialWarp
                : shader3D;
    }

    /** Menu and status-bar text. The enum name has no space; the label does. */
    @Override
    public String toString() {
        return label;
    }

    /**
     * Parse a persisted projection name, accepting the names used before the rename.
     *
     * <p>There are two generations of legacy names, not one. {@code RadialWarp} and
     * {@code RectWarp} are the immediate predecessors. Before those, {@code LogDisk} and
     * {@code PowerDisk} (added 2026 June 10, commit 93d046dc9) were separate Sun-centred disk
     * modes, which the one-parameter family later absorbed as members rather than modes; both
     * therefore resolve to Helioradial. These files are the provenance record for figures that
     * may already be in a paper, so they have to keep opening.
     *
     * <p>Known limitation, stated rather than papered over: the disk-era files record no
     * exponent, because the exponent did not exist as saved state yet. LogDisk implied the
     * logarithmic member and PowerDisk carried an exponent that was never written down, so those
     * sessions restore with the current default exponent rather than their original one. The
     * projection is recovered; the exact stretch is not. Resolving a name deliberately has no
     * side effect on the exponent, since a lookup that silently moved a slider would be worse
     * than a documented gap.
     *
     * @return the mode, or null if the name matches nothing (current or legacy)
     */
    @Nullable
    public static MapMode fromName(String name) {
        return switch (name) {
            case "RadialWarp" -> Helioradial;
            case "RectWarp" -> HelioradialUnrolled;
            case "LogDisk", "PowerDisk" -> Helioradial;
            default -> {
                for (MapMode mode : values())
                    if (mode.name().equals(name))
                        yield mode;
                yield null;
            }
        };
    }

    /**
     * Margin around the helioradial disk, as a fraction of its diameter. Matches the 1.1 that
     * the pre-geometry implementation used against a unit-diameter normalized disk, so the
     * framing is unchanged from what users are accustomed to.
     */
    private static final double HELIORADIAL_MARGIN = 1.1;

    public double baseCameraWidth(Camera camera) {
        return switch (this) {
            // The edge crop, and ONLY the edge crop, sets the helioradial camera. The warp
            // itself is normalized over the full loaded field (Display.fullWarpFieldRadius) and
            // never sees this number, which is what makes the edge a plain zoom: closing it
            // shrinks the camera against a fixed mapping, so everything magnifies together and
            // the rim leaves the frame. Feeding the crop into the warp instead renormalizes the
            // projection and pins the rim; using the camera's own width instead ignores the
            // edge and opens a vignette. Both have been shipped; neither is a crop.
            // Flat: the fragment-space map fills a fixed normalized disk, so the camera is the
            // constant it always was. 3D: the scene is physical, so the camera is the edge crop
            // and the warp is normalized over the full field (see Display.fullWarpFieldRadius).
            case Helioradial -> Display.isHelioradial3D()
                    ? HELIORADIAL_MARGIN * 2 * Display.effectiveWarpOuterRadius()
                    : HELIORADIAL_MARGIN;
            case HelioradialUnrolled -> 1.0;
            case Orthographic, HPC, Latitudinal -> camera.baseCameraWidth();
        };
    }

    /**
     * Whether this mode draws a rotated 3D scene rather than a flat projected map.
     *
     * <p>Helioradial joins Orthographic here because its warp is now geometry: the imagery is a
     * surface mesh, so it shares the ortho path's rotated MVP, depth buffer and world-space
     * layer rendering. Helioradial Unrolled, HPC and Latitudinal stay flat.
     */
    public boolean rendersIn3D() {
        return this == Orthographic || (this == Helioradial && Display.isHelioradial3D());
    }

    public boolean usesWarpLambda() {
        return this == Helioradial || this == HelioradialUnrolled;
    }

    MapMode(GLSLSolarShader _shader, String _label) {
        shader3D = _shader;
        label = _label;
    }

    public MapView createMapView(Camera camera, Position viewpoint, GridType gridType, MapScale[] scales) {
        return MapView.create(camera, viewpoint, gridType, this, scales);
    }
}
