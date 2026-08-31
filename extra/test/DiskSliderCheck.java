package org.helioviewer.jhv.gui.component;

import org.helioviewer.jhv.display.Display;

/**
 * The Disk slider's mapping: reversed, logarithmic, continuous.
 *
 * <p>Two things go wrong quietly in a mapping shaped like this. The direction can invert, which
 * nobody notices until a slider "feels backwards" -- this one already did, built growing rightward
 * against the run of Warp, Edge and Zoom, where further left is a tighter field and so a larger
 * apparent size. And a round trip can drift, so the handle jumps a pixel every time the palette is
 * rebuilt.
 *
 * <p>There is deliberately no sentinel position. An "auto" step adjacent to a continuous range is
 * a discontinuity by construction, and it is unnecessary here because nominal is an ordinary value
 * on the scale: at 1.0 the anchor is returned untouched.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.gui.component.DiskSliderCheck
 */
public final class DiskSliderCheck {

    private static int failures;

    public static void main(String[] args) {
        // Direction: further LEFT is a bigger disk, matching Edge and Zoom.
        double left = ToolBar.sliderToDiskScale(0);
        double middle = ToolBar.sliderToDiskScale(500);
        double right = ToolBar.sliderToDiskScale(1000);
        expect(left > middle && middle > right, "the disk shrinks left to right: " + left + " " + middle + " " + right);

        // The ends reach the stated limits, or the slider cannot express its own range.
        expect(Math.abs(left - Display.DISK_SCALE_MAX) < 1e-9, "leftmost is the maximum, got " + left);
        expect(Math.abs(right - Display.DISK_SCALE_MIN) < 1e-9, "rightmost is the minimum, got " + right);

        // Continuity: no step along the track may change the scale by more than a few percent,
        // which is what "no visual discontinuity anywhere" reduces to on a 1000-step slider.
        double previous = ToolBar.sliderToDiskScale(0);
        for (int v = 1; v <= 1000; v++) {
            double now = ToolBar.sliderToDiskScale(v);
            expect(now < previous && previous / now < 1.02,
                    "step " + v + " jumps from " + previous + " to " + now);
            previous = now;
        }

        // Nominal is reachable, and sits near the left rather than at an end.
        int nominal = ToolBar.diskScaleToSlider(Display.DISK_SCALE_NOMINAL);
        expect(Math.abs(ToolBar.sliderToDiskScale(nominal) - Display.DISK_SCALE_NOMINAL) < 5e-3,
                "the nominal position really is nominal, got " + ToolBar.sliderToDiskScale(nominal));
        expect(nominal > 0 && nominal < 1000, "nominal is not stuck at an end: " + nominal);

        // Round trip: rebuilding the palette must not nudge the handle.
        for (int v = 0; v <= 1000; v += 37) {
            int back = ToolBar.diskScaleToSlider(ToolBar.sliderToDiskScale(v));
            expect(Math.abs(back - v) <= 1, "round trip at " + v + " came back as " + back);
        }

        // Out-of-range input is clamped rather than extrapolated off the ends of the scale.
        expect(ToolBar.sliderToDiskScale(-50) == left, "below the left end clamps to the maximum");
        expect(ToolBar.sliderToDiskScale(1050) == right, "past the right end clamps to the minimum");

        if (failures != 0)
            throw new AssertionError(failures + " disk-slider failure(s)");
        System.out.println("DiskSliderCheck: PASS");
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            if (failures < 6)
                System.out.println("FAIL: " + what);
        }
    }

}
