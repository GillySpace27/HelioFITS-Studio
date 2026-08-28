package org.helioviewer.jhv.display;

/**
 * The grid must be warped by the same mapping as the imagery it annotates.
 *
 * <p>Two uniforms decide where things land in the helioradial view, and they are filled from
 * different places:
 *
 * <ul>
 * <li>the imagery mesh reads {@code ScreenBlock.yStop}, which GLSLSolarShader.bindScreen fills
 * with {@code scale.toMapY(1)};
 * <li>every world-space overlay (grid, point clouds, PFSS, FOV boxes, annotations) reads
 * {@code WarpBlock.outerRadius}, which GLSLWarp.enable fills with
 * {@code scale.warpOuterRadius()}.
 * </ul>
 *
 * <p>Those two numbers must be the same, or a grid ring labelled 10 solar radii is drawn at a
 * different screen radius from the part of the image that is 10 solar radii out. The failure is
 * purely visual: nothing throws, no guard fires, and the picture is simply wrong.
 *
 * <p>They came apart once. GLSLWarp.enable took the radius as a second argument, and when the
 * edge control stopped feeding the warp, the imagery was normalized over the full loaded field
 * while the overlays were still handed the edge crop. The argument is gone now and both read
 * the scale, but this check exists because the seam is invisible in the code: the two values
 * are set in different files, for different UBOs, and only ever meet on screen.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.HelioradialGridAlignmentCheck
 */
public final class HelioradialGridAlignmentCheck {

    private static int failures;

    public static void main(String[] args) {
        // Everything below describes the 3D rendering; flat Helioradial keeps the
        // original fixed-disk framing, which is a different contract.
        Display.setHelioradial3D(true);
        // The identity, across the field sizes and exponents the app actually reaches.
        for (double lambda : new double[]{1, 0.5, 0, -0.5, -1}) {
            Display.setWarpLambda(lambda);
            for (double field : new double[]{1.5, 5, 32, 180, 215, 400}) {
                MapScale scale = MapScale.boxCoxRadial(field);
                near(scale.warpOuterRadius(), scale.toMapY(1), 1e-12,
                     "overlay radius equals imagery yStop at lambda=" + lambda + ", field=" + field);
                near(scale.warpOuterRadius(), field, 1e-12,
                     "the scale reports the field it was built for at lambda=" + lambda + ", field=" + field);
            }
        }

        // The edge crop must not reach the warp mapping. This is the specific regression: the
        // edge moves the camera only, so a scale built for the field keeps reporting the field
        // no matter where the crop sits.
        Display.setWarpLambda(0);
        double field = 180;
        MapScale scale = MapScale.boxCoxRadial(field);
        for (double edge : new double[]{180, 120, 60, 30, 5}) {
            Display.setWarpOuterRadius(edge);
            near(scale.warpOuterRadius(), field, 1e-12,
                 "the edge crop does not move the overlay warp radius (edge=" + edge + ")");
            near(scale.toMapY(1), field, 1e-12,
                 "the edge crop does not move the imagery yStop (edge=" + edge + ")");
        }
        Display.setWarpOuterRadius(0);

        // ... and the distinction is not academic. Warping a ring with the crop instead of the
        // field misplaces it badly, which is what the misaligned grid looked like. If this ever
        // stops being true the assertions above have gone vacuous.
        double ringCorrect = WarpGeometry.warpRadius(scale, 10, field);
        double ringWrong = WarpGeometry.warpRadius(scale, 10, 30);
        double error = Math.abs(ringCorrect - ringWrong) / ringCorrect;
        if (!(error > 0.1)) {
            System.out.printf("FAIL: using the crop instead of the field moves a 10 Rsun ring by only %.4f;%n"
                            + "      the alignment assertions above would not catch a real regression%n", error);
            failures++;
        }

        // A ring and the imagery must place the same physical radius identically, which is the
        // property a viewer actually sees. Checked through both accessors rather than one.
        for (double r : new double[]{1, 3, 10, 60, 180}) {
            double overlay = WarpGeometry.warpRadius(scale, r, scale.warpOuterRadius());
            double imagery = scale.toUnitY(r) * scale.toMapY(1);
            near(overlay, imagery, 1e-12, "ring and imagery agree at r=" + r);
        }

        Display.setWarpLambda(0); // leave the global where the app defaults it

        System.out.println(failures == 0 ? "HelioradialGridAlignmentCheck: PASS" : "HelioradialGridAlignmentCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void near(double got, double want, double tol, String what) {
        if (Double.isNaN(got) || Math.abs(got - want) > tol * Math.max(1, Math.abs(want))) {
            System.out.printf("FAIL: %s -- got %.12f, want %.12f%n", what, got, want);
            failures++;
        }
    }

    private HelioradialGridAlignmentCheck() {}
}
