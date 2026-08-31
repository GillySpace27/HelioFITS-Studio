package org.helioviewer.jhv.display;

/**
 * The per-mode disk-size formulas behind projection-switch invariance.
 *
 * <p>Display.limbFractionAtUnitZoom is what makes Orthographic -> Helioradial -> HPC keep the
 * Sun the same on-screen size: each branch restates a render path's framing (the camera
 * contract, the Box-Cox limb anchor, the HPC scale) as a number, and the switch solves the
 * new zoom from the ratio. A branch that drifts from its render path breaks the invariance
 * silently — the switch still "works", the disk just jumps — so the closed forms are pinned
 * here. The HPC branch reads the live viewpoint through GLRenderer and ImageLayers and
 * cannot run headless; it is the one branch this check leaves to the eye.
 *
 * <p>Run: java -cp bin:extra/test-classes:lib/* org.helioviewer.jhv.display.DiskSizeInvarianceCheck
 */
public final class DiskSizeInvarianceCheck {

    private static int failures;

    public static void main(String[] args) {
        Viewport vp = new Viewport(0, 0, 0, 100, 100, 100);

        // Orthographic, no crop: the disk (diameter 2 in scene units) against the camera.
        Display.setWarpOuterRadius(0);
        near(Display.limbFractionAtUnitZoom(MapMode.Orthographic, vp),
                2 / Display.getCamera().baseCameraWidth(), 1e-12, "orthographic disk against the camera");

        // With an edge crop, Orthographic frames the crop (margin 1.1), so the disk fraction
        // is 2 / (1.1 * 2 * edge) — the same camera contract EdgeScopeCheck pins.
        Display.setWarpOuterRadius(6);
        near(Display.limbFractionAtUnitZoom(MapMode.Orthographic, vp),
                2 / (1.1 * 2 * 6), 1e-12, "orthographic disk under an edge crop");

        // Flat Helioradial at lambda = 1 with a 6 Rsun field: the Box-Cox limb anchor is 1/6
        // of the unit map, inside the fixed 1.1-margin disk. (Edge kept at 6 so the scale is
        // pinned without consulting the loaded layers, which a headless run cannot.)
        Display.setWarpLambda(1);
        near(Display.limbFractionAtUnitZoom(MapMode.Helioradial, vp),
                (1. / 6) / 1.1, 1e-12, "flat helioradial disk at lambda=1");

        // No centered disk, no invariance: these must return 0 so the switch falls back to
        // the plain camera reset instead of solving nonsense.
        expect(Display.limbFractionAtUnitZoom(MapMode.HelioradialUnrolled, vp) == 0, "unrolled has no disk");
        expect(Display.limbFractionAtUnitZoom(MapMode.Latitudinal, vp) == 0, "latitudinal has no disk");

        Display.setWarpOuterRadius(0);
        Display.setWarpLambda(0); // leave the globals where the app defaults them

        System.out.println(failures == 0 ? "DiskSizeInvarianceCheck: PASS" : "DiskSizeInvarianceCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
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

    private DiskSizeInvarianceCheck() {}
}
