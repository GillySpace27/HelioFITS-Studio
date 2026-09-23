package org.helioviewer.jhv.astronomy;

/**
 * The comet track, from Horizons' own output to the numbers the warp solve is handed.
 *
 * <p>Everything here runs against captured text rather than the network, because the part that can
 * silently go wrong is not the fetch: it is the column the elongation is read from, the reference
 * the position angle is measured from, and the unit the radius ends up in. Horizons reports the
 * angle from the CELESTIAL north pole, a coronagraph frame is drawn about the SOLAR one, and the
 * difference is up to 26 degrees: large enough to put a marker on the wrong side of the occulter
 * and small enough that nobody would notice from one frame.
 *
 * <p>The expected values are the same rows, reduced independently. C/2024 G3 came to perihelion on
 * 2025-01-13 at q = 0.093 au, which is 20 solar radii, so a projected closest approach a little
 * inside that is the answer a correct reduction has to give.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.astronomy.CometTrackCheck
 */
public final class CometTrackCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static void near(String what, double got, double want, double tol) {
        expect(what + " (" + got + " vs " + want + ')', Math.abs(got - want) <= tol);
    }

    // Captured 2025-01-13, C/2024 G3 and the Sun, as seen from SOHO, six-hourly, CSV_FORMAT=YES.
    private static final String[] COMET = {
            "2025-Jan-13 00:00, , ,   5.3741,/T,  339.779, 267.039,",
            " 2025-Jan-13 06:00, , ,   5.0934,/T,  351.889, 272.132,",
            " 2025-Jan-13 12:00, , ,   4.9647,/L,    5.192, 277.263,"};
    private static final String[] SUN = {
            "2025-Jan-13 00:00, , , 356.4295, -981.382,  0.97464500836512,  0.0068458,",
            " 2025-Jan-13 06:00, , , 356.3113, -981.347,  0.97464611005918,  0.0084135,",
            " 2025-Jan-13 12:00, , , 356.1933, -981.312,  0.97464743814099,  0.0099862,"};

    public static void main(String[] args) throws Exception {
        String block = "header we do not want\n$$SOE\n" + String.join("\n", COMET) + "\n$$EOE\ntrailer we do not want\n";
        expect("the ephemeris is read from between $$SOE and $$EOE", Comets.rows(block).length == 3);
        try {
            Comets.rows("*** No ephemeris available ***\n");
            expect("a response with no ephemeris throws rather than returning nothing", false);
        } catch (Exception e) {
            expect("a response with no ephemeris throws rather than returning nothing", true);
        }

        near("elongation 5.3741 deg at 0.974645 au is 19.72 solar radii",
                Comets.projectedRadius(5.3741, 0.97464500836512), 19.715339, 1e-4);

        // Past a right angle the tangent projection turns negative, and a negative distance is
        // inside every field, so a comet on the far side of the sky read as being in the picture.
        expect("a right-angle elongation has no plane-of-sky distance, and does not come back negative",
                Comets.projectedRadius(90, 0.974645) >= Comets.OFF_THE_PLANE);
        expect("nor does a far-side one",
                Comets.projectedRadius(108.2121, 0.974645) >= Comets.OFF_THE_PLANE);
        expect("while a coronagraph-sized elongation is unaffected",
                Math.abs(Comets.projectedRadius(5.3741, 0.97464500836512) - 19.715339) < 1e-4);

        Comets.Track track = Comets.parse(COMET, SUN, "C/2024 G3");
        expect("all three rows survive parsing", track.milli().length == 3);

        long t0 = track.milli()[0], t1 = track.milli()[1], t2 = track.milli()[2];
        expect("times are read from Horizons' own month names, six hours apart",
                t1 - t0 == 6 * 3600_000L && t2 - t1 == 6 * 3600_000L);

        near("first sample's radius", track.radius(t0), 19.715339, 1e-4);
        near("last sample's radius", track.radius(t2), 18.205610, 1e-4);
        near("halfway between two samples the radius is halfway too",
                track.radius((t0 + t1) / 2.), 0.5 * (19.715339 + 18.679993), 1e-4);

        near("position angle is measured from SOLAR north, not celestial: 339.779 - 356.4295",
                track.positionAngle(t0), 343.3495, 1e-3);
        near("and through 360 it goes the short way, not the long way round",
                track.positionAngle((t1 + t2) / 2.), 2.288, 1e-2); // 355.5777 -> 8.9987, halfway
        expect("an interpolated angle stays in [0, 360)",
                track.positionAngle((t1 + t2) / 2.) >= 0 && track.positionAngle((t1 + t2) / 2.) < 360);

        near("before the first sample the track holds rather than extrapolating",
                track.radius(t0 - 86400_000L), 19.715339, 1e-4);
        near("and after the last one too", track.radius(t2 + 86400_000L), 18.205610, 1e-4);

        Comets.Track single = Comets.parse(new String[]{COMET[1]}, new String[]{SUN[1]}, "one row");
        expect("a one-sample track answers rather than running off the end of its own array",
                single.radius(t0) == single.radius(t2) && single.positionAngle(t0) == single.positionAngle(t2));

        expect("closest approach is the smallest projected radius", track.closest() == t2);
        near("which is inside the 20 solar radii perihelion, as a projection must be",
                track.minRadius(), 18.205610, 1e-4);
        expect("entry into a 19 R_sun field of view is the first sample inside it, not the closest",
                track.entry(19) == t1);
        expect("with no sample inside the field of view, tracking starts at closest approach",
                track.entry(5) == t2);

        System.out.println(failures == 0 ? "CometTrackCheck: PASS" : "CometTrackCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

}
