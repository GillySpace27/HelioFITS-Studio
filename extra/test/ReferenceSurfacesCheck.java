package org.helioviewer.jhv.layers.grid;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import org.helioviewer.jhv.base.Colors;
import org.helioviewer.jhv.display.SurfaceModel;
import org.helioviewer.jhv.opengl.BufVertex;

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
 * <p>The wireframes are swept by polar angle rather than by radius, so the sweep is checked
 * against that same identity: every vertex a builder emits must satisfy it, or the drawn mesh has
 * left the surface it annotates. And because the celestial sphere is centred on the observer, its
 * extent control is an elongation, which is a claim worth pinning: a dome of the wrong opening
 * angle looks exactly as convincing as the right one.
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

        sweptVertices();

        if (failures != 0)
            throw new AssertionError(failures + " reference-surface failure(s)");
        System.out.println("ReferenceSurfacesCheck: PASS");
    }

    private static final double EARTH = 215; // solar radii

    /**
     * The vertices the builders actually emit: on the surface, and reaching exactly the extent
     * asked for. The extent is an elongation measured at the observer, which for the celestial
     * sphere is its own centre, so 90 degrees has to be the hemisphere of sky facing the Sun.
     */
    private static void sweptVertices() {
        byte[] color = Colors.Blue;

        for (double diameter : new double[]{EARTH, 2 * EARTH}) {
            double worst = 0;
            for (float[] p : vertices(ReferenceSurfaces.sphereVertices(diameter, Math.PI, color, 1))) {
                double r = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
                worst = Math.max(worst, Math.abs(p[2] - SurfaceModel.ThomsonSphere.depth(r, diameter)) / diameter);
            }
            expect(worst < 1e-6, String.format("swept vertices sit on z = r^2/L at L=%.0f (worst %.1e)", diameter, worst));
        }

        for (double extent : new double[]{30, 45, 90, 180}) {
            double maxElong = 0, maxR = 0, maxRho = 0;
            for (float[] p : vertices(ReferenceSurfaces.sphereVertices(2 * EARTH, Math.toRadians(extent), color, 1))) {
                maxElong = Math.max(maxElong, Math.toDegrees(elongation(p)));
                maxR = Math.max(maxR, Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]));
                maxRho = Math.max(maxRho, Math.hypot(p[0], p[1]));
            }
            expect(Math.abs(maxElong - extent) < 0.01,
                    String.format("extent %.0f deg is that elongation at the observer, got %.3f", extent, maxElong));
            double expectedR = 2 * EARTH * Math.sin(Math.toRadians(extent) / 2); // r = L sin(t/2)
            expect(Math.abs(maxR - expectedR) < 0.01 * EARTH,
                    String.format("extent %.0f deg reaches r = %.1f, got %.1f", extent, expectedR, maxR));
            if (extent == 90) {
                expect(Math.abs(maxRho - EARTH) < 0.01 * EARTH,
                        String.format("a hemisphere is widest at the observer's own distance, got %.1f", maxRho));
                expect(Math.abs(maxR - Math.sqrt(2) * EARTH) < 0.01 * EARTH,
                        String.format("a hemisphere reaches sqrt(2) D, got %.1f", maxR));
            }
            if (extent == 180)
                expect(Math.abs(maxR - 2 * EARTH) < 0.01 * EARTH,
                        String.format("the whole sky closes at the anti-solar point r = 2D, got %.1f", maxR));
        }

        // The Thomson sphere still stops where its field does, and closes on the observer beyond it.
        double maxNear = 0;
        for (float[] p : vertices(ReferenceSurfaces.sphereVertices(EARTH, ReferenceSurfaces.polarAngle(30, EARTH), color, 1)))
            maxNear = Math.max(maxNear, Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]));
        expect(Math.abs(maxNear - 30) < 0.05, String.format("a 30 Rsun field draws out to 30, got %.2f", maxNear));

        double maxFull = 0, rhoAtEnd = 0;
        for (float[] p : vertices(ReferenceSurfaces.sphereVertices(EARTH, ReferenceSurfaces.polarAngle(EARTH, EARTH), color, 1))) {
            double r = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            if (r > maxFull) {
                maxFull = r;
                rhoAtEnd = Math.hypot(p[0], p[1]);
            }
        }
        expect(Math.abs(maxFull - EARTH) < 0.01 * EARTH && rhoAtEnd < 0.01 * EARTH,
                String.format("the whole Thomson sphere closes on the observer, r %.1f rho %.2f", maxFull, rhoAtEnd));

        expect(ReferenceSurfaces.sphereVertices(2 * EARTH, 0, color, 1).getCount() == 0, "zero extent draws nothing");
    }

    /** The angle at the observer, at (0, 0, D), between the Sun and the point: its elongation. */
    private static double elongation(float[] p) {
        double x = p[0], y = p[1], z = p[2] - EARTH;
        double len = Math.sqrt(x * x + y * y + z * z);
        return len == 0 ? 0 : Math.acos(Math.clamp(-z / len, -1, 1)); // the Sun lies at -z from the observer
    }

    private static float[][] vertices(BufVertex buf) {
        FloatBuffer floats = buf.toVertexBuffer().duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
        int n = buf.getCount();
        float[][] out = new float[n][3];
        for (int i = 0; i < n; i++) {
            out[i][0] = floats.get(4 * i);
            out[i][1] = floats.get(4 * i + 1);
            out[i][2] = floats.get(4 * i + 2);
        }
        return out;
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
