package org.helioviewer.jhv.layers.grid;

import org.helioviewer.jhv.display.SurfaceModel;

/**
 * The Thomson sphere wireframe has to be the same surface the imagery is placed on, not a
 * decoration that resembles it. If the two ever diverge the picture is quietly lying: the mesh
 * would sit beside the data it claims to annotate, and nothing would flag it.
 *
 * <p>Checked as a geometric identity rather than against sampled values. Every point of the
 * Thomson sphere satisfies x^2 + y^2 + z^2 = D*z, which is Thales' theorem for the sphere of
 * diameter D through the Sun and the observer. That is the definition, so it holds independently
 * of how SurfaceModel happens to be written, and it fails if the depth law drifts.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.layers.grid.ReferenceSurfacesCheck
 */
public final class ReferenceSurfacesCheck {

    private static int failures;

    public static void main(String[] args) {
        for (double D : new double[]{5, 60, 215, 1000}) {
            for (double r = 0; r <= D; r += D / 200) {
                double z = SurfaceModel.ThomsonSphere.depth(r, D);
                double rho2 = Math.max(0, r * r - z * z);
                // x^2 + y^2 + z^2 = rho^2 + z^2 = r^2, so the identity reduces to r^2 == D*z.
                double lhs = rho2 + z * z;
                double rhs = D * z;
                expect(Math.abs(lhs - rhs) <= 1e-6 * Math.max(1, Math.abs(rhs)),
                        String.format("Thales at D=%.0f r=%.2f: %.6f vs %.6f", D, r, lhs, rhs));

                // rho must stay real and never exceed r: the surface curves toward the observer,
                // it does not bulge past the sphere of that radius.
                expect(rho2 <= r * r + 1e-9, String.format("rho <= r at D=%.0f r=%.2f", D, r));
                expect(z >= -1e-12 && z <= D + 1e-9, String.format("z within [0, D] at D=%.0f r=%.2f", D, r));
            }
            // The far pole: at r = D the surface closes on the observer, so rho goes to zero.
            double zEnd = SurfaceModel.ThomsonSphere.depth(D, D);
            expect(Math.abs(zEnd - D) < 1e-9, "at r = D the surface reaches the observer, got " + zEnd);
        }

        // Near the Sun the Thomson sphere and the plane of sky agree, which is why a small field
        // renders flat and that is correct rather than a bug. Pinned so the claim stays honest.
        double D = 215;
        expect(SurfaceModel.ThomsonSphere.depth(4, D) / 4 < 0.02, "under 2% sag at 4 Rsun");
        expect(SurfaceModel.ThomsonSphere.depth(100, D) / 100 > 0.4, "over 40% sag at 100 Rsun");
        expect(SurfaceModel.PlaneOfSky.depth(100, D) == 0, "the plane of sky has no depth, ever");

        // Density is a multiplier on a log-scaled base, and both ends have to stay sane: a slider
        // dragged to either stop must not produce a mesh of two lines or one of thousands.
        for (double field : new double[]{2, 30, 245}) {
            int sparse = rings(field, 0.25), dense = rings(field, 4);
            expect(sparse >= 2, "a sparse mesh still has rings at field " + field + ": " + sparse);
            expect(dense <= 64, "a dense mesh stays bounded at field " + field + ": " + dense);
            expect(sparse <= dense, "density is monotonic at field " + field);
        }
        // A bigger field earns more rings, or a wide view reads as a handful of stray circles.
        expect(rings(245, 1) > rings(4, 1), "a wider field gets a denser mesh");

        if (failures != 0)
            throw new AssertionError(failures + " reference-surface failure(s)");
        System.out.println("ReferenceSurfacesCheck: PASS");
    }

    private static int rings(double field, double density) {
        try {
            java.lang.reflect.Method m = ReferenceSurfaces.class.getDeclaredMethod("ringCount", double.class, double.class);
            m.setAccessible(true);
            return (int) m.invoke(null, field, density);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("ringCount is gone or changed shape", e);
        }
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            if (failures < 8)
                System.out.println("FAIL: " + what);
        }
    }

}
