package org.helioviewer.jhv.identify;

import javax.annotation.Nullable;

import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.MapScale;
import org.helioviewer.jhv.display.MapView;
import org.helioviewer.jhv.display.SurfaceTransition;
import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.display.ViewportMath;
import org.helioviewer.jhv.display.WarpGeometry;
import org.helioviewer.jhv.math.PolarBasis;
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;

/**
 * Where a sight line lands on the canvas, and which sight line is under a canvas point, for
 * Helioradial drawn in 3D (the warp surface mesh).
 *
 * <p>{@link #place} is the single source of truth: it is warpSurface.vert in Java, line for line.
 * A sight line from the layer's observer, at (0, 0, D) looking down -z, meets the mesh's surface
 * at the point the fragment stage samples it from (vWorld, then worldToHelioprojective with D);
 * that point is moved to its warped position (WarpGeometry.warpWorld, which the vertex stage does
 * as {@code vWorld * t * outerRadius / len}), turned by the view rotation and the surface
 * observer's frame (ImageLayer.render: rotateViewInverse of the surface observer, under the view
 * matrix of Transform.ortho) and projected. The mesh depth uses the surface observer's distance
 * (Display.surfaceObserver); the sampling uses the layer's own, and they differ with "per
 * observer" off, so both appear below.
 *
 * <p>{@link #ray} is derived from it: the plane of sky and the solar disk are closed form, and a
 * curved surface takes a few Newton steps on {@code place} from the plane-of-sky answer. A result
 * is returned only when {@code place} puts it back on the pixel, so the pair cannot disagree.
 *
 * <p>The other modes with a sky have a forward map too, closed form in each: HPC, Observer Sky
 * (composed, from each layer's own surface distance), Helioradial and Unrolled (the plane of sky,
 * MapScale and PolarBasis) and Orthographic (the solar disk or the layer's image plane, orbited or
 * not). For those {@link #place} ends with a self-check: the probe at the placed pixel
 * ({@link Probes#exact}) must give the same sight line back, to a fraction of a pixel, or the sight
 * line is not drawn there (hidden behind the disk, off the page, off the field) and the answer is
 * null. So nothing in the render loop solves for a pixel.
 *
 * <p>For 3D Helioradial, null for a point the mesh does not draw: past the Crop (a fragment discard), past the
 * surface's reach or the loaded field, hidden by the solar disk, or a sight line that misses the
 * surface. The surface is drawn from both sides, so a line crossing a curved surface twice goes to
 * the intersection nearer the camera, as the depth buffer does. Occlusion between layers is not
 * modelled: each layer has its own mesh.
 */
public final class SkyScreen {

    private static final double PLANE_Z_EPS = 1e-6;
    private static final double DET_EPS = 1e-12;
    private static final double SOLVED_PIXELS = 0.25;
    private static final double EDGE = 1e-9; // the rim is drawn up to its own edge: rounding must not drop it
    private static final double STEP = 1e-7; // radians, for the finite-difference Jacobian

    /** Whether the identifier sees this view through the 3D warp surface: Helioradial with Render in 3D. */
    public static boolean applies(MapView mv) {
        return mv.isHelioradial() && mv.rendersIn3D();
    }

    /** The probe for canvas point (x, y) in 3D Helioradial: per layer, the sight line that point shows. */
    public static Identifier.Probe probe(MapView mv, Viewport vp, double x, double y) {
        return observer -> ray(mv, vp, observer, x, y);
    }

    /** Calls to {@link #place}, for the cost guard: a frame of marks should cost a count of these, not a solve each. */
    static final java.util.concurrent.atomic.LongAdder PLACES = new java.util.concurrent.atomic.LongAdder();

    /** How near the sight line read back from a placement must be to the one placed, in screen pixels (judgement). */
    private static final double CHECK_PIXELS = 0.1;

    /**
     * The canvas point (AWT pixels) where {@code observer}'s sight line {@code ray} is drawn, or
     * null where this view draws nothing for it (or has no sky).
     */
    @Nullable
    public static double[] place(MapView mv, Viewport vp, Position observer, Vec3 ray) {
        PLACES.increment();
        Vec3 dir = Identifier.unit(ray);
        if (applies(mv)) {
            Placed p = new Frame(mv, vp, observer).place(dir);
            return p == null ? null : new double[]{p.x, p.y};
        }
        double[] pg = forward(mv, vp, observer, dir);
        if (pg == null)
            return null;
        double[] p = canvas(mv, vp, pg[0], pg[1]);
        return agrees(mv, vp, observer, dir, p) ? p : null;
    }

    /**
     * A whole sky's worth of placements for one observer, built once per frame: the stars overlay
     * draws thousands of rays, and neither the camera setup nor the round-trip check of
     * {@link #place} belongs in that loop. Each answer is the one {@link #place} gives, before the
     * check and in the units the overlay draws in (see {@link #page} and {@link #surface}).
     */
    public static Layout layout(MapView mv, Viewport vp, Position observer) {
        return new Layout(mv, vp, observer);
    }

    public static final class Layout {
        private final MapView mv;
        private final Viewport vp;
        private final Position observer;
        @Nullable
        private final Frame frame;

        private Layout(MapView mv, Viewport vp, Position observer) {
            this.mv = mv;
            this.vp = vp;
            this.observer = observer;
            frame = applies(mv) ? new Frame(mv, vp, observer) : null;
        }

        /**
         * The page point (the flat views' scene coordinates, aspect applied) of a unit sight line in
         * HPC, Observer Sky and flat Helioradial, or null where the page has none.
         */
        @Nullable
        public double[] page(Vec3 dir) {
            return frame == null ? forward(mv, vp, observer, Identifier.unit(dir)) : null;
        }

        /**
         * Helioradial in 3D: the point of the selected surface the sight line meets, in the surface
         * observer's frame and before the shader's radial warp (shape.vert applies that), or null
         * where the mesh draws nothing for it. Drawn under {@link #rotation()}.
         */
        @Nullable
        public Vec3 surface(Vec3 dir) {
            PLACES.increment();
            Placed p = frame == null ? null : frame.place(Identifier.unit(dir));
            return p == null ? null : p.world;
        }

        /** The turn from the surface observer's frame to the screen (rotateViewInverse of its quaternion). */
        public Quat rotation() {
            return Display.surfaceObserver(mv.viewpoint(), observer).toQuat();
        }
    }

    /** The sight line from {@code observer} that canvas point (x, y) shows, or null where the view draws nothing. */
    @Nullable
    public static Vec3 ray(MapView mv, Viewport vp, Position observer, double x, double y) {
        if (applies(mv))
            return new Frame(mv, vp, observer).ray(x, y);
        Identifier.Probe probe = Probes.exact(mv, vp, x, y);
        return probe == null ? null : probe.direction(observer);
    }

    /** The self-check: the probe at {@code p} returns {@code dir}, to {@link #CHECK_PIXELS} of a pixel. */
    private static boolean agrees(MapView mv, Viewport vp, Position observer, Vec3 dir, double[] p) {
        Vec3 back = ray(mv, vp, observer, p[0], p[1]);
        if (back == null)
            return false;
        Vec3 next = ray(mv, vp, observer, p[0] + 1, p[1]);
        if (next == null)
            next = ray(mv, vp, observer, p[0] - 1, p[1]);
        double pixel = next == null ? 0 : Identifier.angle(back, next);
        return Identifier.angle(back, dir) <= Math.max(1e-9, CHECK_PIXELS * pixel);
    }

    /** A page point (view coordinates, aspect already applied) to canvas pixels: the inverse of ViewportMath.computeUpX and computeUpY. */
    private static double[] canvas(MapView mv, Viewport vp, double upX, double upY) {
        double width = mv.cameraWidth(vp);
        return new double[]{
                vp.x + ((upX + mv.cameraTranslationX()) / (width * vp.aspect) + 0.5) * vp.width,
                vp.yAWT + (0.5 - (upY + mv.cameraTranslationY()) / width) * vp.height};
    }

    /**
     * The forward map of a flat mode, each the exact inverse of the line {@link Probes#exact}
     * follows for it (and so of the shader): sight line to page point (view coordinates, aspect applied).
     */
    @Nullable
    private static double[] forward(MapView mv, Viewport vp, Position observer, Vec3 dir) {
        if (mv.isLatitudinal())
            return null;
        if (mv.isOrthographic())
            return orthographic(mv, vp, observer, dir);
        MapScale scale = mv.scale(vp);
        double aspect = vp.aspect;
        if (mv.isObserverSky()) {
            Vec2 pt = mv.skyPage(vp, dir, Display.surfaceDistance(mv.viewpoint().distance, observer.distance));
            return pt == null ? null : new double[]{pt.x * aspect, pt.y};
        }
        if (dir.z >= 0) // behind the observer: no page holds it (HPC and the plane of sky are sunward half-spaces)
            return null;
        Vec2 hp = Identifier.helioprojective(dir);
        if (mv.isHpc())
            return new double[]{(scale.toUnitX(Math.toDegrees(hp.x)) - .5) * aspect, scale.toUnitY(Math.toDegrees(hp.y)) - .5};
        // Helioradial and Unrolled: the plane of sky through the Sun, as Probes.plane turns it into angles with the layer's own distance
        double d = observer.distance;
        double px = d * Math.tan(hp.x);
        double py = Math.sqrt(px * px + d * d) * Math.tan(hp.y);
        double r = Math.hypot(px, py);
        if (!Double.isFinite(r))
            return null;
        if (mv.isHelioradial()) {
            double t = r == 0 ? 0 : scale.toUnitY(r);
            if (!(t <= 1)) // imageRadialWarp.frag: t > 1 is discarded
                return null;
            double f = r == 0 ? 0 : .5 * t / r;
            return new double[]{f * px, f * py};
        }
        double theta = PolarBasis.angle(px, py);
        double ux = scale.toUnitX(Math.toDegrees(theta)) - .5, uy = scale.toUnitY(r) - .5;
        return Math.abs(ux) > .5 || Math.abs(uy) > .5 ? null : new double[]{ux * aspect, uy};
    }

    /**
     * imageOrtho.frag forward: the sight line meets the solar disk where it is in front (near
     * hemisphere for the layer and for the view), else the layer's plane of sky through the Sun;
     * the view rotation then carries that point to the screen. Probes.ortho is its inverse.
     */
    @Nullable
    private static double[] orthographic(MapView mv, Viewport vp, Position observer, Vec3 dir) {
        Quat cameraDiff = Quat.rotateWithConjugate(mv.viewRotation(), observer.toQuat());
        double d = observer.distance;
        double od = d * dir.z, disc = od * od - (d * d - 1);
        if (disc >= 0) {
            double s = -od - Math.sqrt(disc);
            if (s > 0) {
                Vec3 p = new Vec3(s * dir.x, s * dir.y, d + s * dir.z);
                if (p.z > 0) {
                    Vec3 v = cameraDiff.rotateVector(p);
                    if (v.z > 0)
                        return new double[]{v.x, v.y};
                }
            }
        }
        if (!(dir.z < 0))
            return null; // a sight line that never meets the plane of sky
        double s = d / -dir.z;
        Vec3 v = cameraDiff.rotateVector(new Vec3(s * dir.x, s * dir.y, 0));
        return new double[]{v.x, v.y};
    }

    /** Where a sight line lands (canvas pixels), and how near the camera (view z, larger is nearer). */
    private record Placed(double x, double y, double z, @Nullable Vec3 world) {}

    private static final class Frame {
        final Viewport vp;
        final double aspect, width, tx, ty;
        final double d;       // the layer's own distance: what the fragment stage samples with
        final double reach;   // L = Dm / k, the diameter of the sphere through the Sun; infinite on the plane of sky
        final boolean curved;
        final MapScale scale;
        final double outer, limbRadius, crop;
        final Quat toView;

        Frame(MapView mv, Viewport _vp, Position observer) {
            vp = _vp;
            aspect = vp.aspect;
            width = mv.cameraWidth(vp);
            tx = mv.cameraTranslationX();
            ty = mv.cameraTranslationY();
            d = observer.distance;
            Position surface = Display.surfaceObserver(mv.viewpoint(), observer);
            double k = SurfaceTransition.blend();
            curved = k > 0 && surface.distance > 0;
            reach = curved ? surface.distance / k : Double.POSITIVE_INFINITY;
            scale = mv.scale(vp);
            outer = scale.warpOuterRadius();
            limbRadius = scale.warpLimb() * outer;
            crop = Display.getWarpOuterRadius();
            toView = Quat.rotateWithConjugate(mv.viewRotation(), surface.toQuat());
        }

        // Transform.ortho: screen = view rotation applied to the point, plus the camera translation
        Placed project(Vec3 warped) {
            Vec3 v = toView.rotateVector(warped);
            double sx = vp.x + ((v.x + tx) / (width * aspect) + 0.5) * vp.width;
            double sy = vp.yAWT + (0.5 - (v.y + ty) / width) * vp.height;
            return new Placed(sx, sy, v.z, null);
        }

        Vec2 up(double x, double y) {
            return new Vec2(ViewportMath.computeUpX(vp, width, tx, x), ViewportMath.computeUpY(vp, width, ty, y));
        }

        /** The mesh point for a surface point of heliocentric {@code radius}, or null where the stages discard it. */
        @Nullable
        Placed at(Vec3 p, double radius) {
            // warpSurface.frag: vSurfaceExcess > 1 and vCropExcess > 1 discard; the mesh ends at t = 1
            if (radius > Math.min(outer, reach) * (1 + EDGE) || (crop > 0 && radius > crop * (1 + EDGE)))
                return null;
            Vec3 warped = WarpGeometry.warpWorld(scale, p, outer);
            Placed s = project(warped);
            Vec3 v = toView.rotateVector(warped);
            double rr = Math.hypot(v.x, v.y);
            // The solar disk is a sphere of the warped limb radius, near hemisphere only (the radius <= 1 branch)
            if (rr < limbRadius) {
                double zs = Math.sqrt(limbRadius * limbRadius - rr * rr);
                for (int sign = 1; sign >= -1; sign -= 2) {
                    Vec3 onDisk = toView.rotateInverseVector(new Vec3(v.x, v.y, sign * zs));
                    if (onDisk.z >= -1e-9 * limbRadius && sign * zs > s.z + 1e-6 * limbRadius)
                        return null; // the sphere is nearer the camera: it hides this point
                }
            }
            return new Placed(s.x, s.y, s.z, p);
        }

        @Nullable
        Placed place(Vec3 dir) {
            Placed best = null;
            // On disk: the unit sphere, near hemisphere, first intersection
            double od = d * dir.z;
            double disc = od * od - (d * d - 1);
            if (disc >= 0) {
                double s = -od - Math.sqrt(disc);
                if (s > 0) {
                    Vec3 p = new Vec3(s * dir.x, s * dir.y, d + s * dir.z);
                    if (p.z >= 0)
                        best = nearer(best, at(p, 1));
                }
            }
            // Off disk: the plane of sky z = 0, or the sphere |p|^2 = L z of the mesh's depth law
            if (!curved) {
                if (dir.z < 0) {
                    double s = d / -dir.z;
                    Vec3 p = new Vec3(s * dir.x, s * dir.y, 0);
                    double radius = Math.hypot(p.x, p.y);
                    if (radius > 1)
                        best = nearer(best, at(p, radius));
                }
            } else {
                double b = (2 * d - reach) * dir.z, c = d * d - reach * d;
                double q2 = b * b - 4 * c;
                if (q2 >= 0) {
                    double q = -0.5 * (b + Math.copySign(Math.sqrt(q2), b));
                    double[] roots = {q, q == 0 ? Double.NaN : c / q};
                    for (double s : roots) {
                        if (!(s > 0))
                            continue;
                        Vec3 p = new Vec3(s * dir.x, s * dir.y, d + s * dir.z);
                        double radius = p.length();
                        if (radius > 1)
                            best = nearer(best, at(new Vec3(p.x, p.y, radius * radius / reach), radius));
                    }
                }
            }
            return best;
        }

        @Nullable
        Vec3 ray(double x, double y) {
            Vec2 u = up(x, y);
            Vec3 best = null;
            double bestZ = Double.NEGATIVE_INFINITY;
            for (Vec3 seed : seeds(u)) {
                Vec3 r = solve(seed, x, y);
                if (r == null)
                    continue;
                Placed p = place(r);
                if (p != null && p.z > bestZ) {
                    best = r;
                    bestZ = p.z;
                }
            }
            return best;
        }

        // First guesses: the disk (both sides of the view's sphere) and the plane of sky through the Sun
        java.util.List<Vec3> seeds(Vec2 u) {
            java.util.List<Vec3> out = new java.util.ArrayList<>(3);
            double rr = Math.hypot(u.x, u.y);
            if (rr < limbRadius) {
                double zs = Math.sqrt(limbRadius * limbRadius - rr * rr);
                for (int sign = 1; sign >= -1; sign -= 2) {
                    Vec3 p = toView.rotateInverseVector(new Vec3(u.x, u.y, sign * zs));
                    if (p.z >= 0)
                        out.add(Identifier.unit(new Vec3(p.x / limbRadius, p.y / limbRadius, p.z / limbRadius - d)));
                }
            }
            double domain = Math.min(Math.min(outer, reach), crop > 0 ? crop : Double.POSITIVE_INFINITY) * (1 - 1e-6); // a seed outside the mesh places nowhere
            Vec3 axis = toView.rotateInverseVector(new Vec3(0, 0, 1));
            Vec3 base = toView.rotateInverseVector(new Vec3(u.x, u.y, 0));
            if (Math.abs(axis.z) >= PLANE_Z_EPS) { // edge-on: the plane is a line on the screen
                double tau = -base.z / axis.z;
                Vec3 w = new Vec3(base.x + tau * axis.x, base.y + tau * axis.y, 0);
                double rw = w.length();
                if (rw > 0) {
                    double radius = Math.min(scale.toMapY(rw / outer), domain);
                    if (radius > 1)
                        out.add(Identifier.unit(new Vec3(w.x * radius / rw, w.y * radius / rw, -d)));
                }
            }
            if (curved) { // the view line through the sphere of the depth law, taken in the warped space as if it were physical
                double bu = base.x * axis.x + base.y * axis.y + base.z * axis.z;
                double b = 2 * bu - reach * axis.z, c = base.x * base.x + base.y * base.y + base.z * base.z - reach * base.z;
                double q = b * b - 4 * c;
                if (q >= 0)
                    for (double tau : new double[]{(-b - Math.sqrt(q)) / 2, (-b + Math.sqrt(q)) / 2}) {
                        Vec3 w = new Vec3(base.x + tau * axis.x, base.y + tau * axis.y, base.z + tau * axis.z);
                        double rw = w.length();
                        if (rw > 0) {
                            double radius = Math.min(scale.toMapY(Math.min(rw, outer) / outer), domain);
                            Vec3 p = new Vec3(w.x * radius / rw, w.y * radius / rw, w.z * radius / rw);
                            out.add(Identifier.unit(new Vec3(p.x, p.y, p.z - d)));
                        }
                    }
            }
            return out;
        }

        /** Newton's method on {@link #place}, in the sight line's (Tx, Ty); null unless it lands within a quarter pixel. */
        @Nullable
        Vec3 solve(Vec3 seed, double x, double y) {
            Vec2 a = Identifier.helioprojective(seed);
            double tx0 = a.x, ty0 = a.y;
            Placed p = place(Identifier.ray(tx0, ty0));
            // A seed can sit outside the cone of sight lines the surface covers (a sphere seen from outside): walk it toward the axis
            for (int i = 0; p == null && curved && i < 60; i++) {
                tx0 *= 0.9;
                ty0 *= 0.9;
                p = place(Identifier.ray(tx0, ty0));
            }
            for (int step = 0; step < 30 && p != null; step++) {
                double ex = x - p.x, ey = y - p.y, err = Math.hypot(ex, ey);
                if (err <= SOLVED_PIXELS * 0.1)
                    break;
                Placed px = place(Identifier.ray(tx0 + STEP, ty0)), py = place(Identifier.ray(tx0, ty0 + STEP));
                if (px == null || py == null)
                    return null;
                double a11 = (px.x - p.x) / STEP, a21 = (px.y - p.y) / STEP;
                double a12 = (py.x - p.x) / STEP, a22 = (py.y - p.y) / STEP;
                double det = a11 * a22 - a12 * a21;
                double scaleSq = (a11 * a11 + a21 * a21) * (a12 * a12 + a22 * a22);
                if (!(Math.abs(det) > DET_EPS * Math.sqrt(scaleSq)) || !Double.isFinite(det))
                    return null; // edge-on or singular: the pixel does not fix a sight line
                double dx = (a22 * ex - a12 * ey) / det, dy = (-a21 * ex + a11 * ey) / det;
                Placed next = null;
                for (double f = 1; f > 0.01 && next == null; f /= 2) { // damped: take the first step that lowers the error
                    Placed trial = place(Identifier.ray(tx0 + f * dx, ty0 + f * dy));
                    if (trial != null && Math.hypot(x - trial.x, y - trial.y) < err) {
                        tx0 += f * dx;
                        ty0 += f * dy;
                        next = trial;
                    }
                }
                if (next == null)
                    return null;
                p = next;
            }
            return p != null && Math.hypot(p.x - x, p.y - y) <= SOLVED_PIXELS ? Identifier.ray(tx0, ty0) : null;
        }

        @Nullable
        private static Placed nearer(@Nullable Placed a, @Nullable Placed b) {
            return b == null || (a != null && a.z >= b.z) ? a : b;
        }
    }

    private SkyScreen() {}
}
