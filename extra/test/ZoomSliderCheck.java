package org.helioviewer.jhv.gui.component;

/**
 * The zoom slider's log mapping, and above all which way round it runs.
 *
 * <p>Direction is the part worth pinning. Every slider in the projection palette runs
 * right-is-bigger, and Zoom and Crop are the two that decide how much sky is on screen, sitting
 * one above the other: if they disagree, dragging both in the same direction moves the view in
 * opposite directions. Nothing fails when that inverts -- it just quietly stops matching its
 * neighbour -- so it is asserted rather than left to the eye.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.gui.component.ZoomSliderCheck
 */
public final class ZoomSliderCheck {

    private static int failures;

    public static void main(String[] args) {
        // Centre is unity: the slider's own double-click default (500) must be "no zoom".
        near(ToolBar.zoomSliderToMagnification(500), 1, 1e-12, "the centre detent is 1x");

        // Direction: right magnifies, left pulls back, matching Crop.
        expect(ToolBar.zoomSliderToMagnification(0) < 1, "the left end is zoomed OUT");
        expect(ToolBar.zoomSliderToMagnification(1000) > 1, "the right end is zoomed IN");
        double previous = 0;
        for (int t = 0; t <= 1000; t += 25) {
            double magnification = ToolBar.zoomSliderToMagnification(t);
            expect(magnification > previous, "magnification rises monotonically at t=" + t);
            previous = magnification;
        }

        // Range: symmetric in log, so 1x sits at the centre rather than off to one side.
        near(ToolBar.zoomSliderToMagnification(0), 1 / 64., 1e-9, "the left end is 1/64x");
        near(ToolBar.zoomSliderToMagnification(1000), 64, 1e-9, "the right end is 64x");

        // Round trip, which is what keeps the wheel's value and the handle in agreement.
        for (int t = 0; t <= 1000; t += 50)
            same(ToolBar.magnificationToZoomSlider(ToolBar.zoomSliderToMagnification(t)), t,
                    "round trip at t=" + t);

        // Off-scale zooms (the wheel is unbounded, the slider is not) park at an end instead of
        // running off it, so the handle stays inside the track and the label tells the truth.
        same(ToolBar.magnificationToZoomSlider(1000), 1000, "far zoomed in parks at the right end");
        same(ToolBar.magnificationToZoomSlider(1e-6), 0, "far zoomed out parks at the left end");

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
