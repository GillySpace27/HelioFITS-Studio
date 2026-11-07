package org.helioviewer.jhv.identify;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.display.ViewportMath;
import org.helioviewer.jhv.math.PolarBasis;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;

/**
 * The cursor as a sight line in each layer's frame, for every view mode that draws the sky.
 *
 * <p>Each mode samples an image layer its own way, and the probe follows the shader for that mode
 * so the body named is the one drawn under the pointer:
 * <ul>
 * <li>HPC and Observer Sky lay the page out in helioprojective angles, and every layer is sampled
 * at those angles in its own frame (imageHpc.frag), so the direction is the same for all layers.</li>
 * <li>Helioradial and Helioradial Unrolled lay out a point of the plane of sky, and each layer
 * turns it into angles with its own observer distance (sampleWarpedHpcColor).</li>
 * <li>Orthographic rotates the screen point into each layer's frame through cameraDiff, takes the
 * solar disk where it is in front and the layer's image plane elsewhere (imageOrtho.frag), and the
 * direction runs from that layer's observer to the point.</li>
 * </ul>
 * Latitudinal maps the solar surface, not the sky, and Helioradial in 3D draws a warped surface
 * mesh: neither has a sight line under the pointer, and both return null.
 */
public final class Probes {

    /** The probe for the pointer at screen (x, y), or null in a mode with no sky under the pointer. */
    @Nullable
    public static Identifier.Probe at(MapView mv, Viewport vp, double x, double y) {
        if (mv.isLatitudinal())
            return null;
        if (mv.isHpc()) {
            Vec2 c = mv.mouseToMap(vp, (int) Math.round(x), (int) Math.round(y));
            return c == Vec2.NAN ? null : helioprojective(Math.toRadians(c.x), Math.toRadians(c.y));
        }
        if (mv.isObserverSky()) {
            Vec2 c = mv.mouseToSkyAngles(vp, (int) Math.round(x), (int) Math.round(y));
            return helioprojective(Math.toRadians(c.x), Math.toRadians(c.y));
        }
        if (mv.isHelioradial() || mv.isHelioradialUnrolled()) {
            if (mv.rendersIn3D())
                return null;
            // Off the page the shaders discard (imageRadialWarp.frag: t > 1; the unrolled map: outside [0, 1])
            double w = mv.cameraWidth(vp);
            double ux = ViewportMath.computeUpX(vp, w, mv.cameraTranslationX(), x);
            double uy = ViewportMath.computeUpY(vp, w, mv.cameraTranslationY(), y);
            if (mv.isHelioradial() ? 2 * Math.hypot(ux, uy) > 1 : Math.abs(ux / vp.aspect) > .5 || Math.abs(uy) > .5)
                return null;
            Vec2 c = mv.mouseToMap(vp, (int) Math.round(x), (int) Math.round(y));
            if (c == Vec2.NAN || !Double.isFinite(c.y))
                return null;
            double theta = Math.toRadians(c.x);
            return plane(PolarBasis.x(c.y, theta), PolarBasis.y(c.y, theta));
        }
        double width = mv.cameraWidth(vp);
        double upX = ViewportMath.computeUpX(vp, width, mv.cameraTranslationX(), x);
        double upY = ViewportMath.computeUpY(vp, width, mv.cameraTranslationY(), y);
        return ortho(mv.viewRotation(), upX, upY);
    }

    /** One direction for every layer: (Tx, Ty) in radians. */
    public static Identifier.Probe helioprojective(double tx, double ty) {
        Vec3 dir = Identifier.ray(tx, ty);
        return observer -> dir;
    }

    /** A point of the plane of sky in solar radii, turned into angles with each layer's own distance. */
    public static Identifier.Probe plane(double px, double py) {
        return observer -> {
            double d = observer.distance;
            return Identifier.ray(Math.atan2(px, d), Math.atan2(py, Math.sqrt(px * px + d * d)));
        };
    }

    private static final double PLANE_Z_EPS = 1e-6;

    /**
     * The orthographic view's point (upX, upY), the view position the shader starts from, under
     * the view rotation; imageOrtho.frag's main and intersectImagePlane, in Java.
     */
    public static Identifier.Probe ortho(Quat viewRotation, double upX, double upY) {
        return observer -> {
            Quat cameraDiff = Quat.rotateWithConjugate(viewRotation, observer.toQuat());
            double r2 = upX * upX + upY * upY;
            Vec3 point = null;
            if (r2 <= 1) {
                Vec3 rotated = cameraDiff.rotateInverseVector(new Vec3(upX, upY, Math.sqrt(1 - r2)));
                if (rotated.z > 0)
                    point = rotated;
            }
            if (point == null) {
                Vec3 altnormal = cameraDiff.rotateVector(Vec3.ZAxis);
                if (Math.abs(altnormal.z) < PLANE_Z_EPS)
                    return null;
                Vec3 hit = new Vec3(upX, upY, -(altnormal.x * upX + altnormal.y * upY) / altnormal.z);
                point = cameraDiff.rotateInverseVector(hit);
            }
            return fromObserver(observer, point);
        };
    }

    private static Vec3 fromObserver(Position observer, Vec3 point) {
        return Identifier.unit(new Vec3(point.x, point.y, point.z - observer.distance));
    }

    private Probes() {}
}
