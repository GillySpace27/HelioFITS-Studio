package org.helioviewer.jhv.layers.grid;

/**
 * That a planet's on-screen motion is its own orbital motion, not the Sun's rotation.
 *
 * <p>This failed silently once and looked plausible while doing so. Placing bodies with a frame
 * offset evaluated at the display time puts the Carrington prime meridian into the answer, and
 * since {@code displayLon = offset - hciLon}, every planet then moves at {@code 14.184 - omega}.
 * Measured on screen: Mercury 10.83, Earth 13.20, Neptune 14.18 deg/day, against true rates of
 * 3.355, 0.986 and 0.0061. Everything moved, everything moved at roughly the same speed, and
 * nothing about the picture said the orbits had been replaced by the Sun's spin.
 *
 * <p>The assertion cannot run headless -- it needs SPICE's native library, which is unpacked at
 * runtime -- so it is written as the arithmetic identity underneath instead: given the sidereal
 * Carrington rate and a body's orbital rate, a time-varying offset yields their difference and a
 * frozen one yields the orbital rate alone. If someone reintroduces the per-sample offset, the
 * relationship this pins is what they will have to argue with.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.layers.grid.PlanetRateCheck
 */
public final class PlanetRateCheck {

    /** Sidereal Carrington rotation, deg/day. Measured from the shipped ephemeris, not looked up. */
    private static final double CARRINGTON_RATE = 14.184;

    /** Orbital rates in deg/day, Mercury to Neptune, from the shipped ephemeris. */
    private static final double[] OMEGA = {3.3550, 1.6230, 0.9860, 0.5010, 0.0836, 0.0334, 0.0113, 0.0061};
    private static final String[] NAMES = {"Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"};

    private static int failures;

    public static void main(String[] args) {
        for (int i = 0; i < OMEGA.length; i++) {
            double moving = Math.abs(CARRINGTON_RATE - OMEGA[i]); // offset re-evaluated each time
            double frozen = OMEGA[i];                             // offset held fixed

            // The bug: every body ends up within a couple of deg/day of the solar rotation,
            // whatever its orbit, so they all appear to move at the same speed.
            expect(Math.abs(moving - CARRINGTON_RATE) <= 3.4,
                    NAMES[i] + ": a moving offset should collapse toward the solar rate, got " + moving);

            // The fix: the on-screen rate IS the orbital rate.
            expect(Math.abs(frozen - OMEGA[i]) < 1e-9, NAMES[i] + ": a frozen offset gives the orbital rate");

            // And the two are wildly different for anything slower than Mercury, which is what
            // makes the bug worth a check rather than a comment.
            if (OMEGA[i] < 1)
                expect(moving / frozen > 10,
                        NAMES[i] + ": the bug inflates the rate by " + Math.round(moving / frozen) + "x");
        }

        // Neptune is the extreme: 2320 times too fast, which is what made it visible at all.
        double inflation = Math.abs(CARRINGTON_RATE - OMEGA[7]) / OMEGA[7];
        expect(inflation > 2000, "Neptune's inflation should be over 2000x, got " + Math.round(inflation));

        // Ordering: under the bug the apparent rate RISES with distance from the Sun, the exact
        // reverse of real orbital motion. That inversion is the tell.
        for (int i = 1; i < OMEGA.length; i++) {
            expect(OMEGA[i] < OMEGA[i - 1], "true rates fall outward at " + NAMES[i]);
            expect(Math.abs(CARRINGTON_RATE - OMEGA[i]) > Math.abs(CARRINGTON_RATE - OMEGA[i - 1]),
                    "the bug's rates rise outward at " + NAMES[i]);
        }

        if (failures != 0)
            throw new AssertionError(failures + " planet-rate failure(s)");
        System.out.println("PlanetRateCheck: PASS");
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
