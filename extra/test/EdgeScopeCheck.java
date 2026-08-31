package org.helioviewer.jhv.display;

/**
 * Which projections the Edge crop acts on, and that Orthographic actually obeys it.
 *
 * <p>The edge (Display.warpOuterRadius) reaches the warp modes through their Box-Cox scale and
 * Orthographic through the camera; HPC and Latitudinal have no radial coordinate a crop in
 * solar radii could act on. usesWarpEdge is what gates the toolbar slider and keeps edge-mode
 * CME tracking engaged across projection switches, so if it drifts, the slider greys out in a
 * mode where the crop still silently acts, or tracking dies on a switch it should survive.
 *
 * <p>Also pins the camera contract: with a crop set, Orthographic frames it with the same
 * margin as 3D Helioradial (mode agreement is what makes switching between them keep the
 * framing); at auto the camera keeps its own framing, which is the default install's look.
 * The auto path reads the live camera and cannot run headless, so it is not asserted here.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.EdgeScopeCheck
 */
public final class EdgeScopeCheck {

    private static int failures;

    public static void main(String[] args) {
        expect(MapMode.Helioradial.usesWarpEdge(), "Helioradial uses the edge");
        expect(MapMode.HelioradialUnrolled.usesWarpEdge(), "HelioradialUnrolled uses the edge");
        expect(MapMode.Orthographic.usesWarpEdge(), "Orthographic uses the edge");
        expect(!MapMode.HPC.usesWarpEdge(), "HPC has no radial crop");
        expect(!MapMode.Latitudinal.usesWarpEdge(), "Latitudinal has no radial crop");

        // The lambda gate must not have widened along with the edge gate.
        expect(!MapMode.Orthographic.usesWarpLambda(), "Orthographic does not use lambda");
        expect(!MapMode.HPC.usesWarpLambda() && !MapMode.Latitudinal.usesWarpLambda(),
                "flat non-warp modes do not use lambda");

        // With a crop set, the orthographic camera is the crop (times the shared margin),
        // and it follows the crop; the camera object is not consulted on this path.
        Display.setWarpOuterRadius(30);
        double cropped = MapMode.Orthographic.baseCameraWidth(null);
        Display.setWarpOuterRadius(60);
        double wider = MapMode.Orthographic.baseCameraWidth(null);
        near(wider / cropped, 2, 1e-12, "orthographic camera follows the edge crop");
        Display.setHelioradial3D(true);
        near(cropped, MapMode.Helioradial.baseCameraWidth(null) / 2, 1e-12,
                "orthographic and 3D helioradial frame the same crop identically"); // 60 vs 30
        Display.setHelioradial3D(false);
        Display.setWarpOuterRadius(0);

        System.out.println(failures == 0 ? "EdgeScopeCheck: PASS" : "EdgeScopeCheck: " + failures + " FAILURE(S)");
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

    private EdgeScopeCheck() {}
}
