package org.helioviewer.jhv.identify;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.DisplaySettings;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.CometEphemeris;
import org.helioviewer.jhv.astronomy.Comets;
import org.helioviewer.jhv.astronomy.Position;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.JplHorizons;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.math.PolarBasis;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The Track comet track's position angle and radius (Comets.parse), against an independent answer:
 * C/2025 R3's Horizons state vectors seen from SOHO's Horizons state vectors (CometSight), with SPICE.
 * The track itself is read from the captured SOHO observer tables, so the two share no reduction.
 *
 * <p>Pins: (1) at the closest approach (13 Rsun), at 23.5 Rsun on the way in, and at the widest
 * elongation of the window (44 degrees), the track's point is the vector answer within the
 * differential aberration Horizons' observer tables carry and the vectors do not, plus 1 arcsec of
 * print precision; (2) the same over every fourth row of the window. It also prints how far the
 * track would move with the Sun's direction taken from Earth (SPICE) instead of from SOHO's states,
 * which is why Comets.parse takes SOHO's. A missing fixture is a FAIL.
 */
public final class CometTrackAngleCheck {

    private static int failures;
    private static final double ARCSEC = Math.PI / 180 / 3600;
    private static final Path DATA = Path.of("extra/test/data");

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("comet-track-angle-check");
        System.setProperty("user.home", home.toString());
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs();
        AppInit.loadSpice();

        check();

        if (failures != 0) {
            System.out.println("CometTrackAngleCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("CometTrackAngleCheck: PASS");
    }

    /**
     * Differential aberration (Horizons' observer tables are apparent, the state vectors are not) for
     * a 30 km/s observer, an estimate as in plan 2.2, plus 1 arcsec for the tables' printed digits.
     */
    private static double tolerance(double elongDeg) {
        return 30 / 299792.458 * Math.sin(Math.toRadians(elongDeg)) / ARCSEC + 1;
    }

    private static void check() throws Exception {
        List<JplHorizons.States> parts = new ArrayList<>();
        for (String p : new String[]{"horizons-comet-c_2025_r3-states-0.txt", "horizons-comet-c_2025_r3-states-1.txt", "horizons-comet-c_2025_r3-states-2.txt"}) {
            String text = fixture(p);
            if (text == null)
                return;
            parts.add(JplHorizons.parseStates(text));
        }
        String sohoText = fixture("horizons-soho-c_2025_r3-states.txt");
        String target = fixture("horizons-comet-c_2025_r3-observer-soho.txt"), sun = fixture("horizons-sun-c_2025_r3-observer-soho.txt");
        if (sohoText == null || target == null || sun == null)
            return;
        CometEphemeris comet = CometEphemeris.of("C/2025 R3", merge(parts), "", 0);
        CometEphemeris soho = CometEphemeris.of("SOHO", JplHorizons.parseStates(sohoText), "", 0);

        Method rows = Comets.class.getDeclaredMethod("rows", String.class);
        Method parse = Comets.class.getDeclaredMethod("parse", String[].class, String[].class, String.class, CometEphemeris.class);
        Method bearing = Comets.class.getDeclaredMethod("bearingAtSun", double[].class, double.class, double.class);
        rows.setAccessible(true);
        parse.setAccessible(true);
        bearing.setAccessible(true);
        String[] targetRows = (String[]) rows.invoke(null, target), sunRows = (String[]) rows.invoke(null, sun);
        Comets.Track track = (Comets.Track) parse.invoke(null, targetRows, sunRows, "C/2025 R3", soho);
        long[] m = track.milli();
        if (m.length != targetRows.length) {
            expect(false, "every row of the SOHO observer fixtures is read (" + m.length + " of " + targetRows.length + ")");
            return;
        }

        // The three named rows
        int closest = 0, widest = 0, inbound = -1;
        double[] elong = new double[m.length];
        for (int i = 0; i < m.length; i++) {
            elong[i] = soT(targetRows[i]);
            if (track.rSun()[i] < track.rSun()[closest])
                closest = i;
            if (elong[i] > elong[widest])
                widest = i;
        }
        for (int i = 0; i < closest; i++)
            if (inbound < 0 || Math.abs(track.rSun()[i] - 23.5) < Math.abs(track.rSun()[inbound] - 23.5))
                inbound = i;
        for (int i : new int[]{closest, inbound, widest}) {
            double sep = separation(track, i, comet, soho);
            expect(sep <= tolerance(elong[i]), String.format(Locale.ROOT,
                    "%s, %.1f Rsun (%.2f deg) from the Sun: the track is %.2f arcsec from the state-vector answer (tolerance %.2f)",
                    new JHVTime(m[i]), track.rSun()[i], elong[i], sep, tolerance(elong[i])));
        }

        // Every fourth row, and the Sun's direction from Earth against from SOHO
        double worstOver = Double.NEGATIVE_INFINITY, worstSun = 0;
        int n = 0;
        for (int i = 0; i < m.length; i += 4) {
            double sep = separation(track, i, comet, soho);
            worstOver = Math.max(worstOver, sep - tolerance(elong[i]));
            n++;

            double[] km = soho.eclipticKm(m[i]);
            double[] fromSoho = unit(Spice.rotate("ECLIPJ2000", "J2000", m[i], new double[]{-km[0], -km[1], -km[2]}));
            double[] fromEarth = unit(Spice.getPosition("EARTH", "SUN", "J2000", new JHVTime(m[i])));
            double ps = psAng(targetRows[i]);
            double a = (double) bearing.invoke(null, fromSoho, elong[i], ps), b = (double) bearing.invoke(null, fromEarth, elong[i], ps);
            double dpa = Math.toRadians(Math.IEEEremainder(a - b, 360));
            worstSun = Math.max(worstSun, Math.abs(dpa) * Math.sin(Math.toRadians(elong[i])) / ARCSEC);
        }
        expect(n >= 500 && worstOver <= 0, String.format(Locale.ROOT,
                "over the whole window, at %d rows, the track is within tolerance of the state-vector answer (worst margin %.2f arcsec)", n, worstOver));
        System.out.printf(Locale.ROOT, "  info the Sun's direction from Earth rather than from SOHO would move the track by up to %.1f arcsec%n", worstSun);
    }

    /** Angle between the track's point (as CometSky places it) and the state-vector sight, arcsec. */
    private static double separation(Comets.Track track, int i, CometEphemeris comet, CometEphemeris soho) throws Exception {
        JHVTime t = new JHVTime(track.milli()[i]);
        double[] km = soho.eclipticKm(t.milli);
        double[][] rot = CometEphemeris.eclipticToCarrington(t.milli);
        double[] c = new double[3];
        for (int r = 0; r < 3; r++)
            c[r] = (rot[r][0] * km[0] + rot[r][1] * km[1] + rot[r][2] * km[2]) * Sun.RadiusKMeterInv;
        Position sohoPos = ModelPlacement.carringtonPosition(t, c[0], c[1], c[2]);
        double radius = track.rSun()[i], theta = Math.toRadians(track.paDeg()[i]);
        Vec3 want = Probes.plane(PolarBasis.x(radius, theta), PolarBasis.y(radius, theta)).direction(sohoPos);
        CometSight.Sight s = CometSight.sight(comet::world, sohoPos, soho::world, true, DisplaySettings.TimeMode.Observer);
        return s == null || want == null ? Double.POSITIVE_INFINITY : Identifier.angle(s.dir(), want) / ARCSEC;
    }

    private static double soT(String row) {
        return column(row, -1);
    }

    private static double psAng(String row) {
        return column(row, 1);
    }

    /** The column {@code offset} from the visibility flag, as Comets.parse finds it. */
    private static double column(String row, int offset) {
        String[] f = row.split(",");
        for (int c = 1; c + 1 < f.length; c++)
            if (f[c].trim().startsWith("/"))
                return Double.parseDouble(f[c + offset].trim());
        throw new IllegalArgumentException("no visibility flag in " + row);
    }

    private static JplHorizons.States merge(List<JplHorizons.States> parts) {
        List<Long> t = new ArrayList<>();
        List<double[]> p = new ArrayList<>(), v = new ArrayList<>();
        for (JplHorizons.States s : parts)
            for (int i = 0; i < s.milli().length; i++)
                if (t.isEmpty() || s.milli()[i] > t.getLast()) {
                    t.add(s.milli()[i]);
                    p.add(new double[]{s.km()[3 * i], s.km()[3 * i + 1], s.km()[3 * i + 2]});
                    v.add(new double[]{s.kms()[3 * i], s.kms()[3 * i + 1], s.kms()[3 * i + 2]});
                }
        long[] tt = new long[t.size()];
        double[] pp = new double[3 * t.size()], vv = new double[3 * t.size()];
        for (int i = 0; i < tt.length; i++) {
            tt[i] = t.get(i);
            System.arraycopy(p.get(i), 0, pp, 3 * i, 3);
            System.arraycopy(v.get(i), 0, vv, 3 * i, 3);
        }
        return new JplHorizons.States(tt, pp, vv);
    }

    private static double[] unit(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    private static String fixture(String name) {
        try {
            return Files.readString(DATA.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            expect(false, "fixture extra/test/data/" + name + " is readable (" + e + ")");
            return null;
        }
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }
}
