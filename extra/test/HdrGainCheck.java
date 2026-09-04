package org.helioviewer.jhv.display;

// Standalone self-check (no test framework in this repo; see extra/test/LUTLabelsCheck.java for
// the pattern). The gain is the one number that decides whether an image is brighter than the
// window, and whether an export is contaminated by it. Both directions are pinned here.
public final class HdrGainCheck {

    public static void main(String[] args) {
        // The capture exemption wins over everything: exports never see a gain.
        assertEq("capturing", 1f, HdrGain.resolve("auto", 10.85, 16, true));
        assertEq("capturing, fixed", 1f, HdrGain.resolve("4", 10.85, 16, true));
        // Auto tracks the display once it reports a headroom.
        assertEq("auto", 10.85f, HdrGain.resolve("auto", 10.85, 16, false));
        // Before the compositor has engaged, auto bootstraps past white on a screen that could
        // offer more, and stays at 1 on one that cannot.
        assertEq("auto, bootstrap", HdrGain.BOOTSTRAP, HdrGain.resolve("auto", 1.0, 16, false));
        assertEq("auto, SDR display", 1f, HdrGain.resolve("auto", 1.0, 1.0, false));
        assertEq("auto, absurd", 1f, HdrGain.resolve("auto", 0.0, 0.0, false));
        // Fixed stops are clamped to [1, 16], never exceed what the screen shows, and fall back to
        // auto when unparsable.
        assertEq("fixed", 4f, HdrGain.resolve("4", 10.85, 16, false));
        assertEq("fixed, too low", 1f, HdrGain.resolve("0.5", 10.85, 16, false));
        assertEq("fixed, too high", 10.85f, HdrGain.resolve("64", 10.85, 16, false));
        assertEq("fixed, screen offers less", 2f, HdrGain.resolve("4", 2.0, 16, false));
        assertEq("fixed, before engagement", HdrGain.BOOTSTRAP, HdrGain.resolve("4", 1.0, 16, false));
        assertEq("fixed, SDR display", 1f, HdrGain.resolve("4", 1.0, 1.0, false));
        assertEq("garbage", 10.85f, HdrGain.resolve("bright", 10.85, 16, false));
        assertEq("null", 10.85f, HdrGain.resolve(null, 10.85, 16, false));
        System.out.println("HdrGainCheck: PASS");
    }

    private static void assertEq(String what, float expected, float actual) {
        if (Math.abs(expected - actual) > 1e-6f)
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }
}
