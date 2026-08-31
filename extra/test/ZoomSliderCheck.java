package org.helioviewer.jhv.gui.component;

/**
 * The zoom slider's log mapping, and above all which way round it runs.
 *
 * <p>Direction is the part worth pinning. Zoom and Edge are the two controls that decide how
 * much sky is on screen, they sit one above the other in the same palette, and Edge's sense is
 * fixed by its own construction: its left end is a 2 R_sun crop and its right end the full
 * loaded field, so left is tight and right is wide. Zoom shipped reading the other way, so
 * dragging both sliders in the same direction moved the view in opposite directions. Nothing
 * fails when that inverts -- it just quietly stops matching its neighbour -- so it is asserted
 * rather than left to the eye.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.gui.component.ZoomSliderCheck
 */
public final class ZoomSliderCheck {

    private static int failures;

    public static void main(String[] args) {
        // Centre is unity: the slider's own double-click default (500) must be "no zoom".
        near(ToolBar.zoomSliderToMagnification(500), 1, 1e-12, "the centre detent is 1x");

        // Direction: left tighter (magnified), right wider, matching Edge.
        expect(ToolBar.zoomSliderToMagnification(0) > 1, "the left end is zoomed IN");
        expect(ToolBar.zoomSliderToMagnification(1000) < 1, "the right end is zoomed OUT");
        double previous = Double.MAX_VALUE;
        for (int t = 0; t <= 1000; t += 25) {
            double magnification = ToolBar.zoomSliderToMagnification(t);
            expect(magnification < previous, "magnification falls monotonically at t=" + t);
            previous = magnification;
        }

        // Range: symmetric in log, so 1x sits at the centre rather than off to one side.
        near(ToolBar.zoomSliderToMagnification(0), 64, 1e-9, "the left end is 64x");
        near(ToolBar.zoomSliderToMagnification(1000), 1 / 64., 1e-9, "the right end is 1/64x");

        // Round trip, which is what keeps the wheel's value and the handle in agreement.
        for (int t = 0; t <= 1000; t += 50)
            same(ToolBar.magnificationToZoomSlider(ToolBar.zoomSliderToMagnification(t)), t,
                    "round trip at t=" + t);

        // Off-scale zooms (the wheel is unbounded, the slider is not) park at an end instead of
        // running off it, so the handle stays inside the track and the label tells the truth.
        same(ToolBar.magnificationToZoomSlider(1000), 0, "far zoomed in parks at the left end");
        same(ToolBar.magnificationToZoomSlider(1e-6), 1000, "far zoomed out parks at the right end");

        System.out.println(failures == 0 ? "ZoomSliderCheck: PASS" : "ZoomSliderCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private static void same(int got, int want, String what) {
        if (got != want) {
            System.out.println("FAIL: " + what + " -- got " + got + ", want " + want);
            failures++;
        }
    }

    private static void near(double got, double want, double tol, String what) {
        if (Double.isNaN(got) || Math.abs(got - want) > tol * Math.max(1, Math.abs(want))) {
            System.out.printf("FAIL: %s -- got %.12f, want %.12f%n", what, got, want);
            failures++;
        }
    }

    private ZoomSliderCheck() {}
}
