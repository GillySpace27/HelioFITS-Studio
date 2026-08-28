package org.helioviewer.jhv.display;

import org.helioviewer.jhv.opengl.GLSLSolarShader;

/**
 * Flat and 3D Helioradial are two implementations, and the toggle must switch all of it together.
 *
 * <p>Flat is a fragment-space inverse map on a full-screen quad (solarRadialWarp.frag) filling a
 * fixed normalized disk. 3D is a surface mesh (warpSurface) in physical solar radii with a
 * rotated MVP and a camera sized by the edge crop. Three things have to move as one: the render
 * path, the shader, and the camera contract. Switch the path without the shader and the mesh
 * shader gets a flat MVP; switch the shader without the camera and the scene is the wrong size.
 *
 * <p>Flat is the default and is load-bearing beyond preference: it is the rendering behind the
 * poster and the paper figures, so a default install has to reproduce them. That is why the
 * default is asserted here rather than left to whoever edits the field next.
 *
 * <p>Also pins the limb continuity the 3D disk depends on. warpSurface.vert deprojects the disk
 * onto the solar sphere and scales it by the limb's warp factor, while the corona branch scales
 * by t * outerRadius. Those two agree at r = 1 only if warpRadius(1) equals limb * outerRadius.
 * If that identity broke, the photosphere and the corona would part company at exactly the limb.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.HelioradialModeCheck
 */
public final class HelioradialModeCheck {

    private static int failures;

    public static void main(String[] args) {
        // Default: flat. The published figures depend on this.
        expect(!Display.isHelioradial3D(), "Helioradial is flat by default");

        Display.setHelioradial3D(false);
        expect(!MapMode.Helioradial.rendersIn3D(), "flat does not take the 3D render path");
        same(MapMode.Helioradial.shader(), GLSLSolarShader.radialWarp, "flat uses the fragment-space shader");

        // Flat framing is a fixed disk: the camera is constant and the edge acts through the
        // scale instead, which is the behaviour the figures were made with.
        Display.setWarpOuterRadius(180);
        double flatWide = MapMode.Helioradial.baseCameraWidth(null);
        Display.setWarpOuterRadius(20);
        double flatTight = MapMode.Helioradial.baseCameraWidth(null);
        near(flatTight, flatWide, 1e-12, "flat camera width ignores the edge");

        Display.setHelioradial3D(true);
        expect(MapMode.Helioradial.rendersIn3D(), "3D takes the 3D render path");
        same(MapMode.Helioradial.shader(), GLSLSolarShader.warpSurface, "3D uses the surface-mesh shader");

        // 3D framing is physical: the camera follows the edge crop.
        Display.setWarpOuterRadius(180);
        double wide = MapMode.Helioradial.baseCameraWidth(null);
        Display.setWarpOuterRadius(90);
        double tight = MapMode.Helioradial.baseCameraWidth(null);
        near(wide / tight, 2, 1e-12, "3D camera width follows the edge");

        // The toggle must not leak into the other projections.
        for (MapMode mode : new MapMode[]{MapMode.Orthographic, MapMode.HPC, MapMode.Latitudinal}) {
            boolean before = mode.rendersIn3D();
            Display.setHelioradial3D(!Display.isHelioradial3D());
            expect(mode.rendersIn3D() == before, mode.name() + " is unaffected by the Helioradial toggle");
            Display.setHelioradial3D(!Display.isHelioradial3D());
        }
        expect(MapMode.HelioradialUnrolled.shader() == MapMode.HelioradialUnrolled.shader(),
               "the unrolled layout has one shader regardless of the toggle");
        Display.setHelioradial3D(true);
        GLSLSolarShader unrolled3D = MapMode.HelioradialUnrolled.shader();
        Display.setHelioradial3D(false);
        same(MapMode.HelioradialUnrolled.shader(), unrolled3D, "the unrolled layout ignores the toggle");

        // Limb continuity: the sphere branch and the corona branch must meet at r = 1.
        for (double lambda : new double[]{1, 0.5, 0, -0.5, -1}) {
            Display.setWarpLambda(lambda);
            for (double field : new double[]{5, 32, 180, 215}) {
                MapScale scale = MapScale.boxCoxRadial(field);
                double sphereBranch = scale.warpLimb() * scale.warpOuterRadius();
                double coronaBranch = WarpGeometry.warpRadius(scale, 1, scale.warpOuterRadius());
                near(sphereBranch, coronaBranch, 1e-9,
                     "disk and corona meet at the limb, lambda=" + lambda + " field=" + field);
            }
        }

        Display.setWarpLambda(0);
        Display.setWarpOuterRadius(0);
        Display.setHelioradial3D(false); // leave the app default in place

        System.out.println(failures == 0 ? "HelioradialModeCheck: PASS" : "HelioradialModeCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void same(Object got, Object want, String what) {
        if (got != want) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private static void near(double got, double want, double tol, String what) {
        if (Double.isNaN(got) || Math.abs(got - want) > tol * Math.max(1, Math.abs(want))) {
            System.out.printf("FAIL: %s -- got %.12f, want %.12f%n", what, got, want);
            failures++;
        }
    }

    private HelioradialModeCheck() {}
}
