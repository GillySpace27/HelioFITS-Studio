package org.helioviewer.jhv.layers.grid;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.SpaceObject;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The ecliptic is the plane of the solar system, and has nothing to do with the Sun's spin.
 *
 * <p>The bug this pins was invisible in a still frame and obvious in an animation: the plane rose
 * and fell over about a month. Two Earth directions sampled a quarter year apart span the ecliptic
 * only if they are taken in an INERTIAL frame. Taken in Carrington, they are separated mostly by
 * the Sun's rotation, and the plane they span is neither the ecliptic nor even steady.
 *
 * <p>The assertion is a physical one rather than a regression baseline: the solar equator is
 * inclined about 7.25 degrees to the ecliptic, so the normal of a correctly built ecliptic sits
 * that far from the solar pole and HOLDS it as the Sun turns. Measured on the shipped ephemeris,
 * the old construction left at 7.482 degrees and reached 6.094 over one rotation while the new one
 * reads 7.252 at every epoch, so the tolerance below separates them by a wide margin.
 *
 * <p>Run: javac needs the ANGLE/SPICE natives on the path; see FitsMetaDataChpolarityCheck.
 */
public final class EclipticCheck {

    /** Carrington's inclination to the ecliptic, IAU 1976. The check's whole point. */
    private static final double INCLINATION_DEG = 7.25;
    private static final double TOLERANCE_DEG = 0.05;

    private static int failures;

    public static void main(String[] args) throws Exception {
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();

        // A full solar rotation, which is the period the old construction drifted over, plus a
        // second epoch half a year away so an annual term would show up too.
        long start = new JHVTime("2025-09-20T00:00:00").milli;
        double first = Double.NaN;
        for (int day = 0; day <= 27; day += 3) {
            double tilt = eclipticTilt(new JHVTime(start + day * 86400_000L));
            expect(Math.abs(tilt - INCLINATION_DEG) < TOLERANCE_DEG,
                    "day " + day + ": the ecliptic normal must sit " + INCLINATION_DEG
                            + " degrees from the solar pole, got " + String.format("%.3f", tilt));
            if (Double.isNaN(first))
                first = tilt;
            else
                expect(Math.abs(tilt - first) < 1e-3,
                        "day " + day + ": the tilt must not move with the Sun's rotation, drifted "
                                + String.format("%.4f", tilt - first) + " degrees from day 0");
        }

        double halfYear = eclipticTilt(new JHVTime(start + 182L * 86400_000L));
        expect(Math.abs(halfYear - INCLINATION_DEG) < TOLERANCE_DEG,
                "half a year later the inclination is still " + INCLINATION_DEG + ", got "
                        + String.format("%.3f", halfYear));

        if (failures != 0)
            throw new AssertionError(failures + " ecliptic failure(s)");
        System.out.println("EclipticCheck: PASS");
    }

    /**
     * The angle between the ecliptic normal and the solar rotation axis, in degrees, built exactly
     * the way ReferenceSurfaces.buildEcliptic builds its basis.
     *
     * <p>In display coordinates the solar pole is +y, which is Position.toQuat's convention and
     * what PlanetMarkers.toDisplay emits.
     */
    private static double eclipticTilt(JHVTime time) {
        double offset = PlanetMarkers.frameOffset(time);
        Vec3 u = earthDirection(time, offset);
        Vec3 w = earthDirection(new JHVTime(time.milli + 91L * 86400_000L), offset);
        Vec3 n = Vec3.cross(u, w);
        double len = n.length();
        if (len == 0)
            return Double.NaN;
        double y = Math.abs(n.y / len); // sign convention does not matter; the angle to the axis does
        return Math.toDegrees(Math.acos(Math.clamp(y, -1, 1)));
    }

    private static Vec3 earthDirection(JHVTime time, double offset) {
        double[] v = PlanetMarkers.hci(SpaceObject.get("Earth"), time);
        if (v == null)
            return new Vec3(0, 0, 0);
        Vec3 d = PlanetMarkers.toDisplay(v, offset);
        double len = d.length();
        return len == 0 ? d : new Vec3(d.x / len, d.y / len, d.z / len);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
