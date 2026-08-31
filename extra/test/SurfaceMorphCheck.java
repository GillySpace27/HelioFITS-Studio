package org.helioviewer.jhv.display;

/**
 * The surface morph, asserted as the depth law the shader actually runs.
 *
 * <p>The morph exists because the geometry was already linear in one number: the vertex stage had
 * {@code depth = r^2 / D} behind a flag compared against 1, so scaling by a blend in [0, 1] walks
 * each vertex along its own line of sight between flat and curved. Nothing is drawn twice. That
 * distinction is the point of the feature, so it is what gets checked: every intermediate blend
 * must be a real surface, lying between the two endpoints and never outside them.
 *
 * <p>Also pinned: the surface's own extent travels with the morph. The Thomson model has no
 * surface past r = D, so what the fragment stage keeps has to close from the full warped field down
 * to D as the surface curves in. Snapping that at either end puts a hard pop in the middle of an
 * otherwise smooth movement, which is the failure mode a reader of this code is most likely to
 * reintroduce by "simplifying" it. (The separate outer crop, {@code cropRadius}, is the user's own
 * field-of-view control and deliberately does not move with the morph.)
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.SurfaceMorphCheck
 */
public final class SurfaceMorphCheck {

    private static int failures;

    public static void main(String[] args) {
        double D = 215, outer = 245;

        for (double r : new double[]{1, 10, 60, 150, 215}) {
            double flat = 0;
            double curved = SurfaceModel.ThomsonSphere.depth(r, D);

            double previous = -1;
            for (int i = 0; i <= 20; i++) {
                double blend = i / 20.;
                double depth = depth(blend, r, D);

                expect(depth >= flat - 1e-9 && depth <= curved + 1e-9,
                        "r=" + r + " blend=" + blend + ": depth " + depth + " outside [0, " + curved + "]");
                expect(depth >= previous - 1e-9, "r=" + r + ": depth must not go backwards at blend " + blend);
                previous = depth;

                // rho stays real, so the surface never folds inside out mid-morph.
                double rho2 = r * r - depth * depth;
                expect(rho2 >= -1e-9, "r=" + r + " blend=" + blend + ": rho went imaginary");
            }

            expect(Math.abs(depth(0, r, D) - flat) < 1e-12, "blend 0 is exactly plane of sky at r=" + r);
            expect(Math.abs(depth(1, r, D) - curved) < 1e-12, "blend 1 is exactly the Thomson sphere at r=" + r);
        }

        // The surface extent travels: the full warped field at 0, the observer distance at 1.
        double last = Double.MAX_VALUE;
        for (int i = 0; i <= 20; i++) {
            double blend = i / 20.;
            double limit = mix(outer, D, blend);
            expect(limit <= last + 1e-9, "the extent must close monotonically, broke at blend " + blend);
            last = limit;
        }
        expect(Math.abs(mix(outer, D, 0) - outer) < 1e-12, "at rest on plane of sky the whole field is surface");
        expect(Math.abs(mix(outer, D, 1) - D) < 1e-12, "fully curved, the surface ends at the observer distance");

        // Smoothstep leaves and arrives at rest, which is what makes it read as one movement.
        expect(Math.abs(ease(0)) < 1e-12 && Math.abs(ease(1) - 1) < 1e-12, "the ease spans the whole way");
        expect(ease(0.02) < 0.02 && ease(0.98) > 0.98, "the ease is slow at both ends");
        double before = -1;
        for (int i = 0; i <= 50; i++) {
            double e = ease(i / 50.);
            expect(e >= before - 1e-12, "the ease never reverses");
            before = e;
        }

        if (failures != 0)
            throw new AssertionError(failures + " surface-morph failure(s)");
        System.out.println("SurfaceMorphCheck: PASS");
    }

    /** The vertex stage's depth law, isolated: warpSurface.vert. */
    private static double depth(double blend, double r, double D) {
        double c = Math.min(r, D);
        return blend * c * c / D;
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** SurfaceTransition's smoothstep. */
    private static double ease(double t) {
        return t * t * (3 - 2 * t);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            if (failures < 8)
                System.out.println("FAIL: " + what);
        }
    }

}
