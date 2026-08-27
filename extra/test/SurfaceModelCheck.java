package org.helioviewer.jhv.display;

import org.helioviewer.jhv.math.Vec3;

// Standalone self-check (no test framework in this repo -- see extra/test/LUTLabelsCheck.java for
// the pattern). Guards the geometry in SurfaceModel, which decides where wide-field coronagraph
// brightness is placed in depth and therefore moves every radial position by 6 to 40 percent
// across a wide field.
//
// This checks the Java side against closed-form geometry. It does NOT check that the GLSL twin in
// resources/glsl/solarCommon.frag agrees numerically -- that needs a GPU and is covered by the
// pixel-diff regression once the mesh path exists. Keep the two edited together.
public final class SurfaceModelCheck {

    private static final double D = 215.0; // 1 au in solar radii, near enough for a geometry test
    private static final double EPS = 1e-9;

    private static int failures;

    public static void main(String[] args) {
        // --- the defining relations -------------------------------------------------------
        // Plane of sky: r = D tan e. Thomson sphere: r = D sin e.
        for (double deg : new double[]{0, 1, 5, 10, 20, 30, 45, 60, 80}) {
            double e = Math.toRadians(deg);
            near(SurfaceModel.PlaneOfSky.heliocentricRadius(e, D), D * Math.tan(e), "POS r at " + deg + " deg");
            near(SurfaceModel.ThomsonSphere.heliocentricRadius(e, D), D * Math.sin(e), "Thomson r at " + deg + " deg");
        }

        // --- the Thomson sphere is actually a sphere ---------------------------------------
        // Every point must satisfy |p|^2 = D * p.z, i.e. lie on the sphere having the
        // Sun-observer line as its diameter. This is the property the whole model rests on, and
        // it is what would break first if depth() and surfacePoint() ever disagreed.
        for (double deg : new double[]{1, 10, 30, 45, 60, 80}) {
            for (double pa : new double[]{0, 90, 180, 270}) {
                Vec3 p = SurfaceModel.ThomsonSphere.surfacePoint(Math.toRadians(pa), Math.toRadians(deg), D);
                double lhs = p.x * p.x + p.y * p.y + p.z * p.z;
                near(lhs, D * p.z, "Thomson sphere identity at e=" + deg + " pa=" + pa);
            }
        }

        // Plane of sky must stay in the plane.
        for (double deg : new double[]{1, 10, 45, 80}) {
            Vec3 p = SurfaceModel.PlaneOfSky.surfacePoint(0, Math.toRadians(deg), D);
            near(p.z, 0, "POS depth at " + deg + " deg");
        }

        // --- radius round-trips through the elongation inverse -----------------------------
        for (SurfaceModel model : SurfaceModel.values()) {
            for (double deg : new double[]{0.5, 5, 20, 45, 70}) {
                double e = Math.toRadians(deg);
                double r = model.heliocentricRadius(e, D);
                near(model.elongation(r, D), e, model + " elongation round-trip at " + deg + " deg");
            }
        }

        // --- surfacePoint magnitude is the heliocentric radius it was asked for -------------
        for (SurfaceModel model : SurfaceModel.values()) {
            for (double deg : new double[]{1, 15, 45}) {
                Vec3 p = model.surfacePoint(Math.toRadians(37), Math.toRadians(deg), D);
                near(Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z),
                        model.heliocentricRadius(Math.toRadians(deg), D),
                        model + " |p| at " + deg + " deg");
            }
        }

        // --- the divergence that motivates the whole feature -------------------------------
        // If these ever stop holding, the two models have collapsed into one and the toggle is
        // doing nothing. Values are sin/tan ratios, derived not measured.
        ratio(10, 0.985, "10 deg");
        ratio(20, 0.940, "20 deg");
        ratio(30, 0.866, "30 deg");
        ratio(45, 0.707, "45 deg");

        // --- the clamp at 90 degrees ------------------------------------------------------
        // Finiteness is NOT enough to prove the clamp is doing its job: Math.tan(toRadians(90))
        // is 1.6e16, large but perfectly finite, and tan(120 deg) is merely negative. Both would
        // sail past a finite() assertion while placing material at an absurd radius or on the
        // wrong side of the Sun. Assert saturation instead, which is what the clamp promises.
        for (SurfaceModel model : SurfaceModel.values()) {
            double atLimit = model.heliocentricRadius(SurfaceModel.MAX_ELONGATION, D);
            finite(atLimit, model + " radius at the clamp");
            near(model.heliocentricRadius(Math.toRadians(90), D), atLimit, model + " saturates at 90 deg");
            near(model.heliocentricRadius(Math.toRadians(120), D), atLimit, model + " saturates past 90 deg");
            near(model.heliocentricRadius(Math.toRadians(1e6), D), atLimit, model + " saturates far past 90 deg");
            // Never behind the observer, and never shrinking as elongation grows.
            double previous = -1;
            for (double deg = 0; deg <= 180; deg += 2.5) {
                double r = model.heliocentricRadius(Math.toRadians(deg), D);
                if (!(r >= 0)) {
                    System.out.println("FAIL: " + model + " negative radius at " + deg + " deg: " + r);
                    failures++;
                }
                if (r < previous - EPS) {
                    System.out.println("FAIL: " + model + " radius decreased at " + deg + " deg: " + r + " < " + previous);
                    failures++;
                }
                previous = r;
            }
            Vec3 p = model.surfacePoint(0, Math.toRadians(90), D);
            finite(p.x, model + " point x at 90 deg");
            finite(p.z, model + " point z at 90 deg");
        }
        // A zero or negative observer distance must not produce NaN either.
        finite(SurfaceModel.ThomsonSphere.depth(1, 0), "depth with D=0");
        finite(SurfaceModel.ThomsonSphere.elongation(1, 0), "elongation with D=0");

        System.out.println(failures == 0 ? "SurfaceModelCheck: PASS" : "SurfaceModelCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    // Thomson radius over plane-of-sky radius, which is sin/tan = cos.
    private static void ratio(double deg, double want, String what) {
        double e = Math.toRadians(deg);
        double got = SurfaceModel.ThomsonSphere.heliocentricRadius(e, D)
                / SurfaceModel.PlaneOfSky.heliocentricRadius(e, D);
        if (Math.abs(got - want) > 1e-3) {
            System.out.printf("FAIL: Thomson/POS ratio at %s -- got %.4f, want %.3f%n", what, got, want);
            failures++;
        }
    }

    private static void near(double got, double want, String what) {
        if (Double.isNaN(got) || Math.abs(got - want) > EPS * Math.max(1, Math.abs(want))) {
            System.out.printf("FAIL: %s -- got %.12f, want %.12f%n", what, got, want);
            failures++;
        }
    }

    private static void finite(double got, String what) {
        if (!Double.isFinite(got)) {
            System.out.println("FAIL: " + what + " -- got " + got + ", want a finite number");
            failures++;
        }
    }

    private SurfaceModelCheck() {}
}
