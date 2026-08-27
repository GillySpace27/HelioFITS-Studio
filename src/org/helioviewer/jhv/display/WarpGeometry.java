package org.helioviewer.jhv.display;

import org.helioviewer.jhv.math.Vec3;

/**
 * The radial warp expressed as a transform on world positions.
 *
 * <p>Until now the warp existed only as a screen-space inverse map inside the fragment shader:
 * a screen radius was unwarped to a physical radius and used to sample a texture. That leaves
 * nothing in the scene for a camera to orbit and nothing for an overlay to be registered
 * against. Here it is a function on world points instead, so the same compression applies to
 * imagery, point clouds, field lines and annotations alike, and the result is ordinary 3D
 * geometry.
 *
 * <p>The direction of a point is preserved and only its heliocentric distance is rescaled:
 *
 * <pre>warpWorld(p) = normalize(p) * warpRadius(|p|)</pre>
 *
 * <p><b>Overlays are not flattened onto the imaged surface.</b> They keep their true positions,
 * rescaled by the same radial law. So a structure genuinely lying near the surface the imagery
 * is painted on will land on it, and one that does not will visibly float off it. That
 * disagreement is information, not a defect.
 *
 * <p><b>lambda = 1 is the identity.</b> {@link MapScale.BoxCoxRadialScale#toUnitY} reduces to
 * {@code r / outerRadius} at lambda = 1, in both the on-disk and off-limb branches, so
 * multiplying back by {@code outerRadius} returns the radius untouched. That is what makes
 * "plane of sky, lambda = 1, no rotation" a meaningful regression target: it must reproduce the
 * unwarped scene exactly, and any drift there is a bug in this file rather than a matter of
 * taste. Guarded by {@code extra/test/WarpGeometryCheck.java}.
 */
public final class WarpGeometry {

    /**
     * Heliocentric distance {@code r} mapped to its warped position, in the same world units.
     * {@code outerRadius} is the edge of the projection, which is its own fixed point.
     */
    public static double warpRadius(MapScale scale, double radius, double outerRadius) {
        if (outerRadius <= 0)
            return radius;
        return scale.toUnitY(radius) * outerRadius;
    }

    /** Inverse of {@link #warpRadius}: a warped distance back to the physical one. */
    public static double unwarpRadius(MapScale scale, double warpedRadius, double outerRadius) {
        if (outerRadius <= 0)
            return warpedRadius;
        return scale.toMapY(warpedRadius / outerRadius);
    }

    /** A world point moved to its warped position, keeping its direction. */
    public static Vec3 warpWorld(MapScale scale, Vec3 p, double outerRadius) {
        double r = Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
        if (r <= 0) // the Sun's centre has no direction to preserve
            return new Vec3(0, 0, 0);
        double f = warpRadius(scale, r, outerRadius) / r;
        return new Vec3(f * p.x, f * p.y, f * p.z);
    }

    private WarpGeometry() {}

}
