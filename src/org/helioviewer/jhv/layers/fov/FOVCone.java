package org.helioviewer.jhv.layers.fov;

import javax.annotation.Nullable;

/**
 * An instrument's field of view as a solid of rays from the spacecraft, in world coordinates
 * (solar radii, the frame ModelPlacement.toWorld gives), with no GL so a check can pin it.
 *
 * <p>Every ray is as long as the spacecraft's distance from the Sun, so a field centred on the Sun
 * ends near the plane of the sky through Sun centre and a heliospheric imager's field reaches as far
 * out as the Sun is from it. Two shapes:
 * <ul>
 * <li>Centred: an annulus of elongation about the Sun line, {@code inner} to {@code outer} degrees
 * (a coronagraph; inner 0 for a disk imager), or a rectangle {@code halfWide} by {@code halfHigh}
 * degrees about it, north up.</li>
 * <li>Off to one side: elongation {@code inner} to {@code outer} degrees toward the unit vector
 * {@code side}, and as wide across as it is long (a square field, which is what HI-1, HI-2,
 * SoloHI and WISPR are to the precision drawn here).</li>
 * </ul>
 */
final class FOVCone {

    /** Points around a circle, the first at solar north. */
    static final int RING = 72;

    /** The apex, and the far end of each edge ray, in world coordinates. */
    final double[] apex;
    /** Far ends of the outer outline, in order around it. */
    final double[][] outer;
    /** Far ends of the inner outline (an annulus's hole), or null. */
    @Nullable
    final double[][] inner;

    private FOVCone(double[] _apex, double[][] _outer, @Nullable double[][] _inner) {
        apex = _apex;
        outer = _outer;
        inner = _inner;
    }

    /** Where the label goes: the middle of the far end. */
    double[] labelAnchor() {
        double[][] pts = outer;
        double x = 0, y = 0, z = 0;
        for (double[] p : pts) {
            x += p[0];
            y += p[1];
            z += p[2];
        }
        if (inner == null)
            return new double[]{x / pts.length, y / pts.length, z / pts.length};
        // An annulus's centre is the Sun line, under the Sun; label its northern edge (position angle 0) instead.
        return pts[0];
    }

    /**
     * An annulus of elongation about the Sun line. {@code north} is solar north in world
     * coordinates; only its part across the line of sight is used.
     */
    static FOVCone annulus(double[] observer, double[] north, double innerDeg, double outerDeg) {
        Frame f = new Frame(observer, north);
        double[][] out = new double[RING][];
        double[][] in = innerDeg > 0 ? new double[RING][] : null;
        for (int i = 0; i < RING; i++) {
            double phi = 2 * Math.PI * i / RING;
            out[i] = f.end(outerDeg, phi);
            if (in != null)
                in[i] = f.end(innerDeg, phi);
        }
        return new FOVCone(observer.clone(), out, in);
    }

    /** A rectangle about the Sun line, half-angles in degrees, north up. */
    static FOVCone rectangle(double[] observer, double[] north, double halfWideDeg, double halfHighDeg) {
        Frame f = new Frame(observer, north);
        double[][] out = {
                f.offset(-halfWideDeg, halfHighDeg), f.offset(halfWideDeg, halfHighDeg),
                f.offset(halfWideDeg, -halfHighDeg), f.offset(-halfWideDeg, -halfHighDeg)};
        return new FOVCone(observer.clone(), out, null);
    }

    /**
     * Elongation {@code innerDeg} to {@code outerDeg} toward {@code side}, as wide across as long.
     * {@code side} need not be perpendicular to the Sun line; its part across it is used. Null when
     * it has none (pointing straight at or away from the Sun).
     */
    @Nullable
    static FOVCone toSide(double[] observer, double[] side, double innerDeg, double outerDeg) {
        double d = len(observer);
        double[] s = scale(observer, -1 / d); // toward the Sun
        double[] u = across(side, s);
        if (u == null)
            return null;
        double[] w = cross(s, u);
        double half = 0.5 * (outerDeg - innerDeg);
        double[][] out = {
                ray(observer, d, s, u, w, innerDeg, -half), ray(observer, d, s, u, w, outerDeg, -half),
                ray(observer, d, s, u, w, outerDeg, half), ray(observer, d, s, u, w, innerDeg, half)};
        return new FOVCone(observer.clone(), out, null);
    }

    /** Elongation from the Sun line of the direction from {@code observer} to {@code p}, in degrees. */
    static double elongation(double[] observer, double[] p) {
        double[] toSun = scale(observer, -1);
        double[] toP = sub(p, observer);
        return Math.toDegrees(Math.acos(Math.clamp(dot(toSun, toP) / (len(toSun) * len(toP)), -1, 1)));
    }

    // ---- the frame across the line of sight ----

    /** s toward the Sun; n solar north across it; e east (toward the observer crossed with north). */
    private static final class Frame {
        final double[] o, s, n, e;
        final double d;

        Frame(double[] observer, double[] north) {
            o = observer;
            d = len(observer);
            s = scale(observer, -1 / d);
            double[] nn = across(north, s);
            n = nn != null ? nn : across(new double[]{1, 0, 0}, s); // seen from a pole: any up
            e = cross(scale(s, -1), n);
        }

        /** The far end of the ray at elongation {@code deg}, position angle {@code phi} from north toward east. */
        double[] end(double deg, double phi) {
            double a = Math.toRadians(deg);
            double[] across = add(scale(n, Math.cos(phi)), scale(e, Math.sin(phi)));
            return add(o, scale(add(scale(s, Math.cos(a)), scale(across, Math.sin(a))), d));
        }

        /** The far end of the ray offset {@code west} degrees west and {@code up} degrees north of the Sun line. */
        double[] offset(double west, double up) {
            double[] dir = add(add(s, scale(e, -Math.tan(Math.toRadians(west)))), scale(n, Math.tan(Math.toRadians(up))));
            return add(o, scale(dir, d / len(dir)));
        }
    }

    private static double[] ray(double[] o, double d, double[] s, double[] u, double[] w, double elongDeg, double acrossDeg) {
        double a = Math.toRadians(elongDeg), b = Math.toRadians(acrossDeg);
        double[] dir = add(scale(s, Math.cos(a)), scale(u, Math.sin(a)));
        dir = add(scale(dir, Math.cos(b)), scale(w, Math.sin(b)));
        return add(o, scale(dir, d));
    }

    // ---- small vector helpers on double[3] ----

    @Nullable
    static double[] across(double[] v, double[] unit) {
        double[] p = sub(v, scale(unit, dot(v, unit)));
        double l = len(p);
        return l < 1e-12 * Math.max(1, len(v)) ? null : scale(p, 1 / l);
    }

    static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] scale(double[] a, double k) {
        return new double[]{a[0] * k, a[1] * k, a[2] * k};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

}
