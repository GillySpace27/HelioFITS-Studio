package org.helioviewer.jhv.display;

/**
 * The projection rename must not orphan sessions written before it.
 *
 * <p>RadialWarp and RectWarp became Helioradial and Helioradial Unrolled. Every session file,
 * SAMP message and script written before that says the old names, and those files are the
 * provenance record for figures that may already be in a paper. If MapMode.fromName stops
 * resolving them the failure is silent: the projection quietly falls back to whatever the
 * default is, and the restored scene is simply wrong rather than obviously broken.
 *
 * <p>Also pins the display labels, since the menu shows toString rather than name, and the only
 * reason toString exists is to put the space into "Helioradial Unrolled".
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.MapModeNamesCheck
 */
public final class MapModeNamesCheck {

    private static int failures;

    public static void main(String[] args) {
        // The whole point of the exercise: pre-rename names still resolve.
        same(MapMode.fromName("RadialWarp"), MapMode.Helioradial, "legacy RadialWarp");
        same(MapMode.fromName("RectWarp"), MapMode.HelioradialUnrolled, "legacy RectWarp");
        // The disk generation, older still. Found by parsing the actual saved sessions rather
        // than by reading the code: six of them use these and none would have restored.
        same(MapMode.fromName("LogDisk"), MapMode.Helioradial, "legacy LogDisk");
        same(MapMode.fromName("PowerDisk"), MapMode.Helioradial, "legacy PowerDisk");

        // Current names resolve, and every constant round-trips through its own name. This is
        // what catches a future constant being added without being reachable from a saved file.
        for (MapMode mode : MapMode.values())
            same(MapMode.fromName(mode.name()), mode, "round trip of " + mode.name());

        // Unknown names must be reported, not silently coerced to a real projection.
        same(MapMode.fromName("NotAProjection"), null, "unknown name yields null");
        same(MapMode.fromName(""), null, "empty name yields null");
        // Labels are not names: feeding a label back in must not resolve, or a caller could
        // round-trip through the menu string and think it had persisted state correctly.
        same(MapMode.fromName("Helioradial Unrolled"), null, "label is not a valid persisted name");

        // Persisted names must stay free of spaces, since that is what distinguishes them from
        // labels and what keeps them safe in SAMP messages and command lines.
        for (MapMode mode : MapMode.values())
            if (mode.name().contains(" ")) {
                System.out.println("FAIL: persisted name contains a space: " + mode.name());
                failures++;
            }

        // Menu labels: distinct, non-empty, and the unrolled one actually reads as two words.
        equalsStr(MapMode.Helioradial.toString(), "Helioradial", "Helioradial label");
        equalsStr(MapMode.HelioradialUnrolled.toString(), "Helioradial Unrolled", "unrolled label");
        equalsStr(MapMode.Orthographic.toString(), "Orthographic", "Orthographic label");
        for (MapMode a : MapMode.values())
            for (MapMode b : MapMode.values())
                if (a != b && a.toString().equals(b.toString())) {
                    System.out.println("FAIL: duplicate label " + a.toString() + " on " + a.name() + " and " + b.name());
                    failures++;
                }

        // Behaviour must have followed the rename rather than staying attached to old constants.
        expect(MapMode.Helioradial.rendersIn3D(), "Helioradial renders in 3D");
        expect(!MapMode.HelioradialUnrolled.rendersIn3D(), "unrolled stays flat");
        expect(MapMode.Helioradial.usesWarpLambda(), "Helioradial uses the exponent");
        expect(MapMode.HelioradialUnrolled.usesWarpLambda(), "unrolled uses the exponent");
        expect(!MapMode.Orthographic.usesWarpLambda(), "Orthographic does not use the exponent");

        System.out.println(failures == 0 ? "MapModeNamesCheck: PASS" : "MapModeNamesCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void same(Object got, Object want, String what) {
        if (got != want) {
            System.out.println("FAIL: " + what + " -- got " + got + ", want " + want);
            failures++;
        }
    }

    private static void equalsStr(String got, String want, String what) {
        if (!want.equals(got)) {
            System.out.println("FAIL: " + what + " -- got \"" + got + "\", want \"" + want + '"');
            failures++;
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private MapModeNamesCheck() {}
}
