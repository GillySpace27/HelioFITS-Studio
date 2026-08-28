package org.helioviewer.jhv.display;

import org.helioviewer.jhv.math.Vec3;

// Standalone self-check (no test framework in this repo -- see extra/test/LUTLabelsCheck.java for
// the pattern). Guards WarpGeometry, which turns the radial warp from a screen-space inverse map
// into a transform on world positions so that imagery, point clouds and overlays share one
// compression.
//
// The load-bearing assertion is the lambda = 1 identity. "Plane of sky, lambda = 1, no rotation
// must reproduce the unwarped scene pixel for pixel" is the regression that proves the geometry
// rewrite changed nothing it should not have, and that regression is worthless if the identity
// does not actually hold here.
public final class WarpGeometryCheck {

    private static final double R = 215.0; // outer radius in solar radii, roughly 1 au
    private static int failures;

    public static void main(String[] args) {
        // --- lambda = 1 is the identity, everywhere -----------------------------------------
        Display.setWarpLambda(1);
        MapScale linear = MapScale.boxCoxRadial(R);
        for (double r : new double[]{0.25, 1, 1.0001, 2, 10, 50, 214.9, R}) {
            near(WarpGeometry.warpRadius(linear, r, R), r, 1e-9, "lambda=1 identity at r=" + r);
        }
        // ... including through the vector form, which must also leave direction alone.
        for (Vec3 p : new Vec3[]{new Vec3(3, 0, 0), new Vec3(0, -40, 0), new Vec3(7, 8, 9)}) {
            Vec3 w = WarpGeometry.warpWorld(linear, p, R);
            near(w.x, p.x, 1e-9, "lambda=1 identity x");
            near(w.y, p.y, 1e-9, "lambda=1 identity y");
            near(w.z, p.z, 1e-9, "lambda=1 identity z");
        }

        // --- at other lambdas the warp must actually do something ---------------------------
        // If this stops holding, the warp has silently become a no-op and the identity test
        // above would still pass, which is exactly the way this check could rot into
        // uselessness.
        for (double lambda : new double[]{-1, -0.5, 0, 0.5}) {
            Display.setWarpLambda(lambda);
            MapScale s = MapScale.boxCoxRadial(R);
            double mid = WarpGeometry.warpRadius(s, 50, R);
            if (Math.abs(mid - 50) < 1e-6) {
                System.out.println("FAIL: lambda=" + lambda + " left r=50 unchanged; warp is a no-op");
                failures++;
            }
            // Compression, not expansion: everything inside the outer edge moves outward in
            // screen terms, i.e. the warped radius exceeds the physical one below the edge.
            if (!(mid > 50)) {
                System.out.println("FAIL: lambda=" + lambda + " did not expand the inner scene: " + mid);
                failures++;
            }
        }

        // --- structural properties, at every lambda -----------------------------------------
        for (double lambda : new double[]{-1, -0.5, 0, 0.5, 1}) {
            Display.setWarpLambda(lambda);
            MapScale s = MapScale.boxCoxRadial(R);
            String at = " (lambda=" + lambda + ")";

            // The outer edge is a fixed point. This is what keeps the projection's own boundary
            // from moving when lambda changes.
            near(WarpGeometry.warpRadius(s, R, R), R, 1e-6, "outer radius is fixed" + at);
            // The Sun's centre stays put and does not produce NaN from normalising a zero vector.
            Vec3 origin = WarpGeometry.warpWorld(s, new Vec3(0, 0, 0), R);
            finite(origin.x + origin.y + origin.z, "origin is finite" + at);
            near(origin.x, 0, 1e-12, "origin x" + at);

            // Monotonic: ordering by distance must survive the warp, or structures swap places.
            double previous = -1;
            for (double r = 0; r <= R; r += R / 400) {
                double w = WarpGeometry.warpRadius(s, r, R);
                finite(w, "warped radius finite at r=" + r + at);
                if (w < previous - 1e-9) {
                    System.out.println("FAIL: warp not monotonic at r=" + r + at + ": " + w + " < " + previous);
                    failures++;
                    break;
                }
                previous = w;
            }

            // Round-trips through the inverse.
            for (double r : new double[]{0.5, 1, 5, 40, 200}) {
                double back = WarpGeometry.unwarpRadius(s, WarpGeometry.warpRadius(s, r, R), R);
                near(back, r, 1e-6, "round-trip at r=" + r + at);
            }

            // Direction is preserved exactly: the warped point stays on its original ray.
            Vec3 p = new Vec3(3, -4, 12); // |p| = 13
            Vec3 w = WarpGeometry.warpWorld(s, p, R);
            double lp = Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
            double lw = Math.sqrt(w.x * w.x + w.y * w.y + w.z * w.z);
            double cos = (p.x * w.x + p.y * w.y + p.z * w.z) / (lp * lw);
            near(cos, 1, 1e-12, "direction preserved" + at);
            near(lw, WarpGeometry.warpRadius(s, lp, R), 1e-9, "magnitude is the warped radius" + at);
        }

        // The warped grid's spokes run from the limb out to scale.toMapY(1), on the claim that
        // this is exactly the projection's outer radius. If it were not, the spokes would stop
        // short of the outermost ring, or overshoot the edge of the imagery.
        for (double outer : new double[]{215, 60, 10, 1.5}) {
            MapScale s = MapScale.boxCoxRadial(outer);
            near(s.toMapY(1), outer, 1e-12, "toMapY(1) is the outer radius at outer=" + outer);
            near(s.toMapY(0), 0, 1e-12, "toMapY(0) is the origin at outer=" + outer);
        }

        Display.setWarpLambda(0); // leave the global where the app defaults it

        System.out.println(failures == 0 ? "WarpGeometryCheck: PASS" : "WarpGeometryCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void near(double got, double want, double tol, String what) {
        if (Double.isNaN(got) || Math.abs(got - want) > tol * Math.max(1, Math.abs(want))) {
            System.out.printf("FAIL: %s -- got %.12f, want %.12f%n", what, got, want);
            failures++;
        }
    }

    private static void finite(double got, String what) {
        if (!Double.isFinite(got)) {
            System.out.println("FAIL: " + what + " -- got " + got);
            failures++;
        }
    }

    private WarpGeometryCheck() {}
}
