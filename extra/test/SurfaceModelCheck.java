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

        // --- the surface passes through the observer ---------------------------------------
        // Thales stated as an endpoint rather than as an identity: the sphere's diameter runs
        // from the Sun to the observer, so its far end IS the observer at (0, 0, D). This is
        // what the blue observer marker in 3D Helioradial exists to show -- the modelled
        // surface must close exactly on that dot once the loaded field reaches 1 au, and a
        // visible gap between them means the field stops short, not that the model is wrong.
        // Asserted at MAX_ELONGATION rather than at 90 degrees because that is the clamp the
        // renderer actually evaluates; the residual gap is the clamp's, and is bounded here so
        // that a wider clamp cannot quietly move the surface away from the observer.
        Vec3 far = SurfaceModel.ThomsonSphere.surfacePoint(0, SurfaceModel.MAX_ELONGATION, D);
        double gap = Math.sqrt(far.x * far.x + far.y * far.y + (far.z - D) * (far.z - D));
        if (!(gap < 0.02 * D)) {
            System.out.printf("FAIL: Thomson surface should converge on the observer -- gap %.4f Rsun of %.1f%n", gap, D);
            failures++;
        }
        // ... and it approaches from the near side, never overshooting past the observer.
        if (far.z > D + EPS) {
            System.out.printf("FAIL: Thomson surface overshoots the observer -- z=%.6f > D=%.1f%n", far.z, D);
            failures++;
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

        // The domain limit, and why it is a refusal rather than a clamp.
        //
        // depth() clamps its radius, so past r = D it returns a constant D while rho = sqrt(r^2 -
        // D^2) keeps growing: the surface extrudes into a flat sheet at fixed depth that renders
        // exactly like corona correctly placed on a plane. Seen with a 245 Rsun field from 66 Rsun,
        // three quarters of the picture was that sheet. canDescribe is what stops it being drawn.
        double DD = 66;
        expect(SurfaceModel.ThomsonSphere.depth(DD, DD) == SurfaceModel.ThomsonSphere.depth(4 * DD, DD),
                "depth() really does pin past r = D, which is what makes the sheet");

        expect(!SurfaceModel.ThomsonSphere.canDescribe(66, 245), "a field past the observer is undescribable");
        expect(SurfaceModel.ThomsonSphere.canDescribe(215, 100), "a field inside the observer is fine");
        expect(SurfaceModel.ThomsonSphere.canDescribe(215, 215), "reaching exactly the observer is the boundary case");
        expect(!SurfaceModel.ThomsonSphere.canDescribe(0, 10), "no observer distance, nothing to describe");
        expect(SurfaceModel.PlaneOfSky.canDescribe(1, 1e9), "the plane of sky has no domain limit");

        // canDescribe reports, it does not gate: refusing the mode outright was tried and locked
        // the Thomson sphere off in exactly the wide-field, near-Sun views it exists for. The
        // renderer clips the undescribable part instead, in warpSurface.vert/.frag via
        // vSurfaceExcess, which is where the sheet is actually prevented from reaching the screen.
        expect(!SurfaceModel.ThomsonSphere.canDescribe(66, 245),
                "the 66-from-245 case still reports as lossy, it is simply no longer refused");

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

    private static void expect(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL: " + what);
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
