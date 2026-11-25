package org.helioviewer.jhv.astronomy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.JplHorizons;

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
 * <p>PsAng is measured at the comet, so the angle at the Sun is solved for first (Comets.bearingAtSun)
 * and needs the Sun's direction from SOHO. Here SOHO is held at ecliptic longitude 113 degrees, on
 * the ecliptic (judgement: about where it was that day; any fixed place pins the plumbing). The
 * expected angles come from RA/Dec spherical trig and bisection rather than the vectors and Newton
 * steps Comets uses (the Python in the comment above {@code soho}).
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

    // Computed with this script (python3), which prints 342.7010, 355.3269 and 9.1559:
    //   import math
    //   EPS = math.radians(84381.448 / 3600)  # IAU 1976 obliquity, as the Horizons fixture header states
    //   def sun_radec(lon_deg, lat_deg):
    //       # SOHO at ecliptic (lon, lat); the Sun is opposite
    //       l, b = math.radians(lon_deg + 180), math.radians(-lat_deg)
    //       x, y, z = math.cos(b)*math.cos(l), math.cos(b)*math.sin(l), math.sin(b)
    //       y, z = y*math.cos(EPS) - z*math.sin(EPS), y*math.sin(EPS) + z*math.cos(EPS)
    //       return math.atan2(y, x), math.asin(z)
    //   def dest(a, d, brg, e):
    //       d2 = math.asin(math.sin(d)*math.cos(e) + math.cos(d)*math.sin(e)*math.cos(brg))
    //       a2 = a + math.atan2(math.sin(brg)*math.sin(e)*math.cos(d), math.cos(e) - math.sin(d)*math.sin(d2))
    //       return a2, d2
    //   def bearing(a1, d1, a2, d2):  # initial bearing from 1 to 2, east of north
    //       return math.atan2(math.sin(a2-a1)*math.cos(d2), math.cos(d1)*math.sin(d2) - math.sin(d1)*math.cos(d2)*math.cos(a2-a1))
    //   def wrap(x): return math.remainder(x, 2*math.pi)
    //   def at_sun(sa, sd, elong, psang):
    //       e, psi = math.radians(elong), math.radians(psang)
    //       def f(phi):
    //           ta, td = dest(sa, sd, phi, e)
    //           away = bearing(ta, td, sa, sd) + math.pi   # back toward the Sun, turned round
    //           return wrap(away - psi)
    //       lo, hi = psi - 0.5, psi + 0.5
    //       assert f(lo) < 0 < f(hi), (f(lo), f(hi))
    //       for _ in range(200):
    //           mid = (lo + hi)/2
    //           if f(mid) < 0: lo = mid
    //           else: hi = mid
    //       return math.degrees((lo+hi)/2)
    //   sa, sd = sun_radec(113.0, 0.0)
    //   print("sun ra %.4f dec %.4f" % (math.degrees(sa) % 360, math.degrees(sd)))
    //   for el, ps, npa in ((5.3741, 339.779, 356.4295), (5.0934, 351.889, 356.3113), (4.9647, 5.192, 356.1933)):
    //       print("%.4f" % ((at_sun(sa, sd, el, ps) - npa) % 360))
    private static CometEphemeris soho(long from, long to) {
        double l = Math.toRadians(113), au = 1.4959787e8;
        double[] km = {au * Math.cos(l), au * Math.sin(l), 0};
        return CometEphemeris.of("SOHO", new JplHorizons.States(new long[]{from, to},
                new double[]{km[0], km[1], km[2], km[0], km[1], km[2]}, new double[6]), "", 0);
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("comet-track-check");
        System.setProperty("user.home", home.toString());
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice(); // the angle at the Sun turns SOHO's ecliptic position into J2000
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

        long day = 86400_000L, first = new org.helioviewer.jhv.time.JHVTime("2025-01-13T00:00:00").milli;
        CometEphemeris soho = soho(first - day, first + day);
        Comets.Track track = Comets.parse(COMET, SUN, "C/2024 G3", soho);
        expect("all three rows survive parsing", track.milli().length == 3);

        long t0 = track.milli()[0], t1 = track.milli()[1], t2 = track.milli()[2];
        expect("times are read from Horizons' own month names, six hours apart",
                t1 - t0 == 6 * 3600_000L && t2 - t1 == 6 * 3600_000L);

        near("first sample's radius", track.radius(t0), 19.715339, 1e-4);
        near("last sample's radius", track.radius(t2), 18.205610, 1e-4);
        near("halfway between two samples the radius is halfway too",
                track.radius((t0 + t1) / 2.), 0.5 * (19.715339 + 18.679993), 1e-4);

        near("position angle is the angle at the Sun (PsAng 339.779 turned round 5.37 deg) from SOLAR north (NP.ang 356.4295)",
                track.positionAngle(t0), 342.7010, 1e-3);
        near("and through 360 it goes the short way, not the long way round",
                track.positionAngle((t1 + t2) / 2.), 2.2414, 1e-3); // 355.3269 -> 9.1559, halfway
        expect("an interpolated angle stays in [0, 360)",
                track.positionAngle((t1 + t2) / 2.) >= 0 && track.positionAngle((t1 + t2) / 2.) < 360);

        near("before the first sample the track holds rather than extrapolating",
                track.radius(t0 - 86400_000L), 19.715339, 1e-4);
        near("and after the last one too", track.radius(t2 + 86400_000L), 18.205610, 1e-4);

        Comets.Track single = Comets.parse(new String[]{COMET[1]}, new String[]{SUN[1]}, "one row", soho);
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
