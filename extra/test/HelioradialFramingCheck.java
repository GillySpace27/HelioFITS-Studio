package org.helioviewer.jhv.display;

/**
 * The Crop control must crop and magnify, not renormalize the projection.
 *
 * <p>Its own documented intent is "a radial crop, a linear zoom-in independent of the lambda
 * warp". Two behaviours have been mistaken for that and neither is it:
 *
 * <ul>
 * <li><b>Vignette.</b> The warp drew out to the crop radius in physical units while the camera
 * width ignored the crop, so lowering it shrank the content inside an unchanged frame and opened
 * a growing empty border.
 * <li><b>Renormalize.</b> The crop fed the warp's own normalization, so lowering it
 * redistributed structure inside a rim that never moved. Features did drift outward, but the
 * outer boundary of the data stayed pinned at the same screen radius, which reads as the
 * picture rearranging itself rather than as a zoom. This is what the projection did before and
 * after the geometry rewrite, so restoring the old framing did not fix it.
 * </ul>
 *
 * <p>A crop is neither. The warp mapping is fixed by the loaded data, and the crop decides only
 * how much of it the camera shows, so everything magnifies together and the rim leaves the
 * frame. The three assertions below are exactly that: the warp does not depend on the crop, a
 * fixed feature magnifies as the crop closes, and the rim eventually goes off-frame.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.HelioradialFramingCheck
 */
public final class HelioradialFramingCheck {

    private static int failures;

    public static void main(String[] args) {
        // Everything below describes the 3D rendering; flat Helioradial keeps the
        // original fixed-disk framing, which is a different contract.
        Display.setHelioradial3D(true);
        Display.setWarpLambda(0);

        // The warp is normalized over the loaded field, which the crop must never move.
        // fullWarpFieldRadius reads the layer stack, whose initialization needs SPICE natives
        // that are not present in a headless run, so this one assertion is reported as skipped
        // rather than quietly dropped. The camera-side assertions below carry the rest and do
        // not depend on it.
        try {
            double fullA = Display.fullWarpFieldRadius();
            Display.setWarpOuterRadius(37);
            double fullB = Display.fullWarpFieldRadius();
            Display.setWarpOuterRadius(212);
            double fullC = Display.fullWarpFieldRadius();
            near(fullB, fullA, 1e-12, "the crop does not move the warp's own extent");
            near(fullC, fullA, 1e-12, "the crop does not move the warp's own extent (again)");
        } catch (Throwable t) {
            System.out.println("SKIP: warp-extent independence needs the layer stack (" +
                               t.getClass().getSimpleName() + "); camera-side assertions still run");
        }

        // With no layers loaded the field falls back to a floor, so the rest of the check works
        // against a fixed stand-in rather than depending on what happens to be open.
        double full = 180;
        MapScale scale = MapScale.boxCoxRadial(full);

        double[] crops = {180, 120, 60, 30};
        double prevFeature = -1, prevRim = -1, widthPerCrop = -1;
        for (double crop : crops) {
            Display.setWarpOuterRadius(crop);
            double cameraWidth = MapMode.Helioradial.baseCameraWidth(null);

            // Closing the crop must shrink the camera in proportion, which is what magnifies
            // everything. Compared across iterations, not against itself.
            if (widthPerCrop < 0)
                widthPerCrop = cameraWidth / crop;
            else
                near(cameraWidth / crop, widthPerCrop, 1e-12,
                     "camera width stays a fixed multiple of the crop at " + crop);

            double feature = WarpGeometry.warpRadius(scale, 10, full) / cameraWidth;
            double rim = WarpGeometry.warpRadius(scale, full, full) / cameraWidth;

            if (prevFeature >= 0) {
                if (!(feature > prevFeature)) {
                    System.out.printf("FAIL: closing the crop to %.0f did not magnify (%.4f then %.4f)%n",
                                      crop, prevFeature, feature);
                    failures++;
                }
                if (!(rim > prevRim)) {
                    System.out.printf("FAIL: closing the crop to %.0f did not push the rim outward%n", crop);
                    failures++;
                }
            }
            prevFeature = feature;
            prevRim = rim;
        }

        // At the widest crop the whole field must be visible, and at the tightest it must not:
        // that difference is the crop, and without it the assertions above could all hold while
        // nothing ever actually left the frame.
        Display.setWarpOuterRadius(full);
        double rimWide = WarpGeometry.warpRadius(scale, full, full) / MapMode.Helioradial.baseCameraWidth(null);
        Display.setWarpOuterRadius(30);
        double rimTight = WarpGeometry.warpRadius(scale, full, full) / MapMode.Helioradial.baseCameraWidth(null);
        if (!(rimWide < 0.5)) {
            System.out.printf("FAIL: at the full crop the rim is at %.4f; the whole field should fit%n", rimWide);
            failures++;
        }
        if (!(rimTight > 0.5)) {
            System.out.printf("FAIL: at a tight crop the rim is at %.4f; it should be cropped away%n", rimTight);
            failures++;
        }

        Display.setWarpOuterRadius(0); // back to auto, the app default

        System.out.println(failures == 0 ? "HelioradialFramingCheck: PASS" : "HelioradialFramingCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void near(double got, double want, double tol, String what) {
        if (Double.isNaN(got) || Math.abs(got - want) > tol * Math.max(1, Math.abs(want))) {
            System.out.printf("FAIL: %s -- got %.12f, want %.12f%n", what, got, want);
            failures++;
        }
    }

    private HelioradialFramingCheck() {}
}
