package org.helioviewer.jhv.display;

/**
 * The edge control must be a zoom, not a vignette.
 *
 * <p>Moving the outer-radius ("edge") control changes which physical range of the corona is
 * rendered. It must NOT change how large that range is drawn: the warped disk should always
 * span the same fraction of the frame, so the user sees the field of view change rather than
 * the content shrink inside a fixed frame.
 *
 * <p>This broke once and the symptom was not obvious from the code. When the helioradial
 * projection became real geometry, its extent went from a fixed normalized disk to physical
 * solar radii, but the camera width was left reading the camera's own physical width, which
 * does not follow the edge. Shrinking the edge then shrank the drawn disk inside an unchanged
 * frame, which reads as a vignette closing in rather than a zoom.
 *
 * <p>The invariant below is the whole property in one line: disk diameter over camera width is
 * a constant, independent of where the edge is placed.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.HelioradialFramingCheck
 */
public final class HelioradialFramingCheck {

    private static int failures;

    public static void main(String[] args) {
        double reference = Double.NaN;

        // Spans three decades of outer radius: a K-Cor-sized field, a LASCO-sized one, and the
        // full PUNCH field. If framing depended on the edge at all, this range would expose it.
        for (double outer : new double[]{5, 10, 60, 180, 215, 400}) {
            Display.setWarpOuterRadius(outer);
            double effective = Display.effectiveWarpOuterRadius();
            near(effective, outer, 1e-12, "the edge control reaches effectiveWarpOuterRadius at " + outer);

            // Camera is unused by this branch; a null here fails loudly if that ever changes.
            double cameraWidth = MapMode.Helioradial.baseCameraWidth(null);

            // The warp's outer edge is a fixed point, so the drawn disk has radius = outer.
            MapScale scale = MapScale.boxCoxRadial(effective);
            double diskDiameter = 2 * WarpGeometry.warpRadius(scale, effective, effective);

            double fraction = diskDiameter / cameraWidth;
            if (Double.isNaN(reference))
                reference = fraction;
            else
                near(fraction, reference, 1e-12,
                     "apparent disk size is unchanged by the edge (outer=" + outer + ")");

            // Framing must also leave a margin rather than run the disk to the frame edge.
            if (!(fraction > 0.5 && fraction < 1)) {
                System.out.printf("FAIL: disk fills %.4f of the frame at outer=%.0f; want between 0.5 and 1%n",
                                  fraction, outer);
                failures++;
            }
        }

        // The invariant must be a real number, not an accident of everything being zero.
        if (!(reference > 0)) {
            System.out.println("FAIL: framing fraction is not positive, so the checks above are vacuous");
            failures++;
        }

        // The camera width has to track the edge, which is the part that actually regressed.
        // Doubling the edge must double the width; a constant width is exactly the bug.
        Display.setWarpOuterRadius(50);
        double narrow = MapMode.Helioradial.baseCameraWidth(null);
        Display.setWarpOuterRadius(100);
        double wide = MapMode.Helioradial.baseCameraWidth(null);
        near(wide / narrow, 2, 1e-12, "doubling the edge doubles the camera width");

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
