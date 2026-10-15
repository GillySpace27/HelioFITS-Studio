package org.helioviewer.jhv.display;

import java.util.List;

import org.json.JSONObject;

/**
 * The Compare palette's swipe divider lands where the shader looks for it (HS-11).
 *
 * <p>imageCommon.frag compares gl_FragCoord.x, which is in framebuffer pixels measured from the
 * drawable's left edge, against the divider x that Compare hands bindDisplay. So the divider must
 * be measured from each viewport's own origin (multiview puts the second panel at x = width) and
 * in the drawable's pixels (a HiDPI drawable is twice the window's points, and Display builds its
 * viewports from the drawable). A divider computed from the viewport width alone lands in the
 * first panel for every panel; one computed in window points lands at half the place on a Retina
 * screen. Both are asserted here. Also pinned: the blink rate's clamp and half-period, and that a
 * session saves Compare's choices only while it is on, and never the blink phase.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.CompareSplitCheck
 */
public final class CompareSplitCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        // One panel, 1000 drawable pixels wide.
        Viewport single = new Viewport(0, 0, 0, 1000, 800, 800);
        expect("centre of a single viewport is x 500, got " + Compare.splitPixel(single.x, single.width, 0.5),
                Compare.splitPixel(single.x, single.width, 0.5) == 500f);

        // Multiview, two panels side by side: the second starts at 1000.
        Viewport right = new Viewport(1, 1000, 0, 1000, 800, 800);
        expect("the second panel's centre is x 1500 (measured from its own origin), got "
                        + Compare.splitPixel(right.x, right.width, 0.5),
                Compare.splitPixel(right.x, right.width, 0.5) == 1500f);

        // HiDPI: the same window at scale 2 is a 2000-pixel drawable, and the viewport is built from it.
        Viewport retina = new Viewport(1, 2000, 0, 2000, 1600, 1600);
        expect("at scale 2 a quarter of the second panel is x 2500, got " + Compare.splitPixel(retina.x, retina.width, 0.25),
                Compare.splitPixel(retina.x, retina.width, 0.25) == 2500f);

        expect("a fraction outside [0, 1] is held to the viewport's edges",
                Compare.splitPixel(100, 400, -1) == 100f && Compare.splitPixel(100, 400, 3) == 500f);

        Compare.setSplit(0.75);
        expect("swipeX reads the stored fraction against the viewport: 0.75 of the second panel is 1750, got " + Compare.swipeX(right),
                Compare.swipeX(right) == 1750f);

        // Blink: 0.5 to 8 Hz, each layer up for half a cycle.
        expect("2 Hz shows each layer for 250 ms, got " + Compare.halfPeriodMillis(2), Compare.halfPeriodMillis(2) == 250);
        expect("rates are held to [0.5, 8] Hz", Compare.clampRate(100) == 8 && Compare.clampRate(0) == 0.5
                && Compare.clampRate(Double.NaN) == Compare.DEFAULT_RATE);
        expect("the fastest blink is 63 ms a side, got " + Compare.halfPeriodMillis(1000), Compare.halfPeriodMillis(1000) == 63);

        // Persistence: nothing while off, the choices (not the phase) while on.
        Compare.setMode(Compare.Mode.Off);
        expect("Compare off writes nothing into a session", Compare.toJson() == null);
        Compare.setRate(4);
        Compare.setSplit(0.3);
        Compare.setALeft(false);
        Compare.setMode(Compare.Mode.Swipe);
        JSONObject saved = Compare.toJson();
        expect("Compare on writes mode, rate, split and side: " + saved, saved != null
                && "Swipe".equals(saved.optString("mode")) && saved.optDouble("rate") == 4
                && saved.optDouble("split") == 0.3 && !saved.optBoolean("aLeft", true));
        expect("the blink phase is never written", saved != null && !saved.toString().contains("showing"));
        Compare.setMode(Compare.Mode.Off);
        Compare.fromJson(saved, List.of());
        expect("a saved session restores the mode, rate and split", Compare.getMode() == Compare.Mode.Swipe
                && Compare.getRate() == 4 && Compare.getSplit() == 0.3 && !Compare.isALeft());
        Compare.fromJson(new JSONObject().put("mode", "Hologram"), List.of());
        expect("a mode from a newer build restores as Off", Compare.getMode() == Compare.Mode.Off);
        Compare.fromJson(null, List.of());
        expect("a session without the key leaves Compare off", Compare.getMode() == Compare.Mode.Off);

        System.out.println(failures == 0 ? "CompareSplitCheck: ok" : "CompareSplitCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
