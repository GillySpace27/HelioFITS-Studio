package org.helioviewer.jhv.wcs;

import org.helioviewer.jhv.math.Mat2;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.metadata.Region;

/**
 * The auto crop frames on the edge of the field, not on its corner.
 *
 * <p>A square frame's corner is further from the Sun than its edge, and the intuition is that it
 * is further by √2. That is true of the angle and false of the picture, because the plane of sky
 * is reached through a tangent: at PUNCH's ±46° of elongation the edge lands near 223 R☉ and the
 * corner near 467, a factor of 2.1. Framing the view on the corner figure left the mosaic sitting
 * in the middle of a page whose outer half held data in four shrinking wedges and nothing else,
 * and the unrolled view ran its radial axis out to 500 R☉ for a mosaic that stops around 200.
 *
 * <p>Pinned here rather than in the auto-crop caller because the fault is geometric and silent:
 * both numbers are plausible radii, nothing throws, and the only symptom is a view that looks
 * mysteriously zoomed out.
 */
final class AutoCropRadiusCheck {

    // A PUNCH L3 mosaic: 4096 pixels of 0.0225°, so ±46.08° of elongation, zenithal equidistant.
    private static final double DISTANCE = 215; // R☉, near enough 1 AU
    private static final double HALF_ANGLE = Math.toRadians(0.0225 * 4096 / 2);

    public static void main(String[] args) {
        // ARC measures angle as plane radius, so region units are DISTANCE * angle.
        double half = DISTANCE * HALF_ANGLE;
        WcsHeader header = new WcsHeader(WcsHeader.Projection.ARC, new float[6], DISTANCE, Vec2.ZERO, Mat2.IDENTITY);
        Region region = new Region(-half, -half, 2 * half, 2 * half);

        double inscribed = ImageBounds.inscribed(header, DISTANCE, region);
        double radial = ImageBounds.radial(header, DISTANCE, region);

        double expectedEdge = DISTANCE * Math.tan(HALF_ANGLE);
        double expectedCorner = DISTANCE * Math.tan(HALF_ANGLE * Math.sqrt(2));
        near("edge", inscribed, expectedEdge);
        near("corner", radial, expectedCorner);

        // The whole point: the two are not a √2 apart, so picking the wrong one is not a detail.
        if (radial / inscribed < 2)
            throw new AssertionError("corner/edge = " + radial / inscribed + ", expected > 2");

        // The Sun outside the frame has no inscribed circle, and the caller needs the 0 to notice.
        Region offset = new Region(3 * half, 3 * half, 2 * half, 2 * half);
        if (ImageBounds.inscribed(header, DISTANCE, offset) != 0)
            throw new AssertionError("a field that excludes the Sun reported an inscribed radius");

        System.out.println("ok AutoCropRadiusCheck edge=" + Math.round(inscribed) + " corner=" + Math.round(radial));
    }

    private static void near(String what, double actual, double expected) {
        if (Math.abs(actual - expected) > 0.01 * expected)
            throw new AssertionError(what + " radius " + actual + ", expected " + expected);
    }

}
