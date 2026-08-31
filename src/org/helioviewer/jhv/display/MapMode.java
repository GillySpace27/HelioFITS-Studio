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
    Latitudinal(GLSLSolarShader.lati, "Latitudinal"),
    // Last because it is the only one that is not centred on the Sun. Everything above answers
    // "what does the corona look like"; this one answers "what is in that direction", which is a
    // different question and is why it gets its own aim and field controls.
    ObserverSky(GLSLSolarShader.sky, "Observer Sky");

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
            // The edge crop sizes the helioradial camera, so closing it magnifies. The warp itself
            // is normalized over the full loaded field (Display.fullWarpFieldRadius) and never
            // sees this number, so the mapping holds still while the framing tightens.
            //
            // On its own that was indistinguishable from the Zoom slider, which is the complaint
            // this addresses: both simply made everything bigger. What separates them is the
            // fragment-stage discard at the crop radius (warpSurface.vert/.frag). The edge now
            // cuts the picture to a hard circle AND magnifies it, which is a zoom by crop; Zoom
            // magnifies with no boundary at all. Same direction, visibly different operations.
            //
            // Sizing the camera by the full field instead was tried and is worse in two ways: it
            // shrinks the picture rather than magnifying it, and it makes this method reach the
            // layer stack unconditionally, which needs SPICE and takes three checks headless.
            //
            // Flat: the fragment-space map fills a fixed normalized disk, so the camera is the
            // constant it always was.
            case Helioradial -> Display.isHelioradial3D()
                    ? HELIORADIAL_MARGIN * 2 * Display.effectiveWarpOuterRadius()
                    : HELIORADIAL_MARGIN;
            case HelioradialUnrolled -> 1.0;
            // The edge crop reaches Orthographic too, sizing the camera exactly as in 3D
            // Helioradial, so edge-mode CME tracking is available in the plain sky view;
            // auto (no crop) keeps the camera's own framing.
            case Orthographic -> {
                double edge = Display.getWarpOuterRadius();
                yield edge > 0 ? HELIORADIAL_MARGIN * 2 * edge : camera.baseCameraWidth();
            }
            case HPC, Latitudinal -> camera.baseCameraWidth();
            // A fixed map filling the normalized domain, like the unrolled layout: the angular
            // field is set in degrees by the sky scale, so the camera must not also be sizing it.
            // Zoom still multiplies this, which magnifies the page without changing what is on it.
            case ObserverSky -> 1.0;
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

    /**
     * Whether the Edge crop (Display.warpOuterRadius) acts on this projection. The warp modes
     * crop through their scale; Orthographic crops through the camera. HPC and Latitudinal
     * have no radial coordinate a crop in solar radii could act on.
     */
    public boolean usesWarpEdge() {
        return usesWarpLambda() || this == Orthographic;
    }

    MapMode(GLSLSolarShader _shader, String _label) {
        shader3D = _shader;
        label = _label;
    }

    public MapView createMapView(Camera camera, Position viewpoint, GridType gridType, MapScale[] scales) {
        return MapView.create(camera, viewpoint, gridType, this, scales);
    }
}
