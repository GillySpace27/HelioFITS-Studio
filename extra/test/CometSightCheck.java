package org.helioviewer.jhv.identify;

import java.io.File;
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
import org.helioviewer.jhv.math.Quat;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.metadata.FitsMetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.metadata.XMLMetaDataContainer;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.view.uri.FITSImage;
import org.helioviewer.jhv.view.uri.URIView;

/**
 * Where each image layer's spacecraft saw a comet (comet3d plan, revision 2, section 2.9,
 * CometSightCheck), with SPICE.
 *
 * <p>Pins: (1) a path built from SPICE's Venus states gives the sight line the identifier's own
 * Venus code gives, from Earth and from STEREO-A; (2) C/2025 R3 from Horizons states, seen from
 * SOHO placed by Horizons, lands where Horizons' own SOHO observer tables (S-O-T, PsAng and the
 * Sun's NP.ang) put it; (3) observationTime undoes FitsMetaData.adjustTime in all three
 * time modes; (4) the light-time loop converges to t - R0/(c+v) on a receding straight path;
 * (5) with the STEREO COR2 fixture header, a comet behind the Sun, inside the occulter, outside
 * the field and in the field gets BEHIND_SUN, OCCULTED, OUTSIDE_FIELD and NONE, and the sight's
 * plane point is the one built; (6) a path fetched for [t0, t1] (CometEphemeris.window) still gives
 * a sight at t0 with light time on; (7) the observer source each layer reports. A missing fixture
 * is a FAIL.
 */
public final class CometSightCheck {

    private static int failures;
    private static final long MINUTE = 60_000L, HOUR = 3_600_000L, DAY = 86_400_000L;
    private static final double ARCSEC = Math.PI / 180 / 3600;
    private static final Path DATA = Path.of("extra/test/data");

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("comet-sight-check");
        System.setProperty("user.home", home.toString());
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs(); // setTimeMode writes a setting
        AppInit.loadSpice();

        checkVenus();
        checkHorizons();
        checkObservationTime();
        checkLightTime();
        checkVisibility();
        checkPadding();
        checkObserverSource();

        if (failures != 0) {
            System.out.println("CometSightCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("CometSightCheck: PASS");
    }

    /** Venus as a comet: SPICE's ECLIPJ2000 states (Rsun and v/c) back to km and km/s, every {@code step} from {@code start}. */
    private static CometEphemeris venus(long start, long end, long step) throws Exception {
        int n = (int) ((end - start) / step) + 1;
        long[] t = new long[n];
        double[] km = new double[3 * n], kms = new double[3 * n];
        for (int i = 0; i < n; i++) {
            t[i] = start + i * step;
            double[] s = Spice.getState("SUN", "VENUS", "ECLIPJ2000", new JHVTime(t[i]));
            for (int k = 0; k < 3; k++) {
                km[3 * i + k] = s[k] / Sun.RadiusKMeterInv;
                kms[3 * i + k] = s[3 + k] * Sun.CLIGHT / 1000;
            }
        }
        return CometEphemeris.of("Venus", new JplHorizons.States(t, km, kms), "", 0);
    }

    // Row 1
    private static void checkVenus() throws Exception {
        JHVTime t = new JHVTime("2026-04-25T00:00:00");
        CometEphemeris v = venus(t.milli - 2 * DAY, t.milli + 2 * DAY, 6 * HOUR);
        for (String who : new String[]{"EARTH", "STEREO AHEAD"}) {
            Position obs = Spice.getCarrington(who, t);
            CometSight.Sight s = obs == null ? null : CometSight.sight(v::world, obs, null, true, DisplaySettings.TimeMode.Observer);
            Vec3 want = obs == null ? null : Identifier.bodyDirection(Bodies.byName("Venus"), obs, true);
            double sep = s == null || want == null ? Double.POSITIVE_INFINITY : Identifier.angle(s.dir(), want) / ARCSEC;
            expect(sep <= 0.05, String.format(Locale.ROOT, "Venus as a comet, seen from %s, is where Identifier.bodyDirection puts Venus (%.4f arcsec)", who, sep));
        }
    }

    private static String fixture(String name) {
        try {
            return Files.readString(DATA.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            expect(false, "fixture extra/test/data/" + name + " is readable (" + e + ")");
            return null;
        }
    }

    // Row 2. Within FIELD_RSUN, the plan's 5 arcsec (differential aberration <= 2.9 arcsec at 30 Rsun,
    // computed in plan 2.2, plus print precision). Over the whole window, out to 44 degrees, 15 arcsec
    // (estimate: differential aberration v/c sin(e) for a 30 km/s observer at 45 degrees,
    // python3 -c "import math; print(30/299792.458*math.sin(math.radians(45))*206265)" = 14.6).
    private static final double FIELD_RSUN = 32;

    private static void checkHorizons() throws Exception {
        String[] pieces = {"horizons-comet-c_2025_r3-states-0.txt", "horizons-comet-c_2025_r3-states-1.txt", "horizons-comet-c_2025_r3-states-2.txt"};
        List<JplHorizons.States> parts = new ArrayList<>();
        for (String p : pieces) {
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

        // Today's track, by Comets' own (package-private) parser, and the raw rows it was read from
        Method rows = Comets.class.getDeclaredMethod("rows", String.class);
        Method parse = Comets.class.getDeclaredMethod("parse", String[].class, String[].class, String.class, CometEphemeris.class);
        rows.setAccessible(true);
        parse.setAccessible(true);
        String[] targetRows = (String[]) rows.invoke(null, target), sunRows = (String[]) rows.invoke(null, sun);
        Comets.Track track = (Comets.Track) parse.invoke(null, targetRows, sunRows, "C/2025 R3", soho);
        long[] m = track.milli();
        if (m.length != targetRows.length || m.length != sunRows.length) {
            expect(false, "every row of the SOHO observer fixtures is read (" + m.length + " of " + targetRows.length + ")");
            return;
        }

        // Horizons measures PsAng at the comet, from the celestial north there, and the Sun's pole
        // angle at the Sun, so subtracting one from the other is good near the Sun only. The reference
        // here is Horizons' own numbers (S-O-T, PsAng, NP.ang) read without that shortcut: the comet is
        // put on the sky where the great circle from the Sun reaches it with Horizons' PsAng, and its
        // angle is then measured at the Sun. Comets.parse now does the same (CometTrackAngleCheck).
        double worst = 0, worstOff = 0, worstTrack = 0, field = 0, fieldOff = 0, maxElong = 0;
        int n = 0, nField = 0;
        for (int i = 0; i < m.length; i += 4) {
            JHVTime t = new JHVTime(m[i]);
            double[] km = soho.eclipticKm(t.milli);
            double[][] rot = CometEphemeris.eclipticToCarrington(t.milli);
            double[] c = new double[3];
            for (int r = 0; r < 3; r++)
                c[r] = (rot[r][0] * km[0] + rot[r][1] * km[1] + rot[r][2] * km[2]) * Sun.RadiusKMeterInv;
            Position sohoPos = ModelPlacement.carringtonPosition(t, c[0], c[1], c[2]);

            double[] sunDir = Spice.rotate("ECLIPJ2000", "J2000", t.milli, new double[]{-km[0], -km[1], -km[2]});
            double[] tf = horizonsRow(targetRows[i]);
            double npAng = Double.parseDouble(sunRows[i].split(",")[3].trim());
            double theta = Math.toRadians(bearingAtSun(unit(sunDir), Math.toRadians(tf[0]), Math.toRadians(tf[1])) - npAng);
            double radius = track.rSun()[i];
            maxElong = Math.max(maxElong, tf[0]);
            Vec3 want = Probes.plane(PolarBasis.x(radius, theta), PolarBasis.y(radius, theta)).direction(sohoPos);
            double trackTheta = Math.toRadians(track.paDeg()[i]);
            Vec3 today = Probes.plane(PolarBasis.x(radius, trackTheta), PolarBasis.y(radius, trackTheta)).direction(sohoPos);

            CometSight.Sight s = CometSight.sight(comet::world, sohoPos, soho::world, true, DisplaySettings.TimeMode.Observer);
            CometSight.Sight off = CometSight.sight(comet::world, sohoPos, soho::world, false, DisplaySettings.TimeMode.Observer);
            if (s == null || want == null || off == null || today == null) {
                worst = Double.POSITIVE_INFINITY;
                continue;
            }
            double sep = Identifier.angle(s.dir(), want) / ARCSEC, sepOff = Identifier.angle(off.dir(), want) / ARCSEC;
            worst = Math.max(worst, sep);
            worstOff = Math.max(worstOff, sepOff);
            worstTrack = Math.max(worstTrack, Identifier.angle(today, want) / ARCSEC);
            n++;
            if (radius <= FIELD_RSUN) {
                field = Math.max(field, sep);
                fieldOff = Math.max(fieldOff, sepOff);
                nField++;
            }
        }
        expect(nField >= 30 && field <= 5, String.format(Locale.ROOT,
                "C/2025 R3 seen from SOHO (Horizons states, light time on) is where the SOHO observer tables put it, within %.0f Rsun of the Sun, at %d times (worst %.2f arcsec; %.0f arcsec without light time)", FIELD_RSUN, nField, field, fieldOff));
        expect(n >= 300 && worst <= 15, String.format(Locale.ROOT,
                "and over the whole window, out to %.0f deg from the Sun, at %d times (worst %.2f arcsec; %.0f arcsec without light time)", maxElong, n, worst, worstOff));
        System.out.printf(Locale.ROOT, "  info the Track comet track (Comets.parse) is off this reference by up to %.2f arcsec over the window%n", worstTrack);
    }

    /** S-O-T and PsAng, degrees, from one row of a Horizons observer table, found as Comets.parse finds them. */
    private static double[] horizonsRow(String row) {
        String[] f = row.split(",");
        for (int c = 1; c + 1 < f.length; c++)
            if (f[c].trim().startsWith("/"))
                return new double[]{Double.parseDouble(f[c - 1].trim()), Double.parseDouble(f[c + 1].trim())};
        throw new IllegalArgumentException("no visibility flag in " + row);
    }

    /**
     * The position angle at the Sun (from celestial north there, degrees) of the point {@code elong}
     * from the Sun whose extended Sun-to-target great circle has position angle {@code psAng} at that
     * point (from celestial north there): Horizons' PsAng, solved for by Newton's method.
     */
    private static double bearingAtSun(double[] sun, double elong, double psAng) {
        double phi = psAng;
        for (int k = 0; k < 50; k++) {
            double f = wrap(psAt(sun, elong, phi) - psAng), h = 1e-7;
            double d = wrap(psAt(sun, elong, phi + h) - psAt(sun, elong, phi)) / h;
            double step = f / d;
            phi -= step;
            if (Math.abs(step) < 1e-13)
                break;
        }
        return Math.toDegrees(phi);
    }

    private static double psAt(double[] sun, double elong, double phi) {
        double[][] ne = northEast(sun);
        double[] tgt = new double[3];
        for (int i = 0; i < 3; i++)
            tgt[i] = Math.cos(elong) * sun[i] + Math.sin(elong) * (Math.cos(phi) * ne[0][i] + Math.sin(phi) * ne[1][i]);
        double st = dot(sun, tgt);
        double[] away = {st * tgt[0] - sun[0], st * tgt[1] - sun[1], st * tgt[2] - sun[2]};
        double[][] at = northEast(tgt);
        return Math.atan2(dot(away, at[1]), dot(away, at[0]));
    }

    /** Unit north and east at {@code p} on the celestial sphere (J2000). */
    private static double[][] northEast(double[] p) {
        double[] north = unit(new double[]{-p[2] * p[0], -p[2] * p[1], 1 - p[2] * p[2]});
        double[] east = unit(new double[]{-p[1], p[0], 0});
        return new double[][]{north, east};
    }

    private static double wrap(double a) {
        return Math.IEEEremainder(a, 2 * Math.PI);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] unit(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
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

    // Row 3
    private static void checkObservationTime() throws Exception {
        Method adjust = FitsMetaData.class.getDeclaredMethod("adjustTime", JHVTime.class, double.class, double.class);
        adjust.setAccessible(true);
        JHVTime dateObs = new JHVTime("2026-04-25T00:16:00");
        double earth = Sun.getEarth(dateObs).distance;
        DisplaySettings.TimeMode before = DisplaySettings.getTimeMode();
        try {
            for (DisplaySettings.TimeMode mode : DisplaySettings.TimeMode.values())
                for (double d : new double[]{earth * Sun.L1Factor, 0.3 * Sun.MeanEarthDistance}) {
                    DisplaySettings.setTimeMode(mode);
                    JHVTime shifted = (JHVTime) adjust.invoke(null, dateObs, d, earth);
                    JHVTime back = CometSight.observationTime(new Position(shifted, d, 1, 0.1), mode);
                    expect(Math.abs(back.milli - dateObs.milli) <= 1, String.format(Locale.ROOT,
                            "observationTime undoes adjustTime in %s mode at %.1f Rsun (off by %d ms)", mode, d, back.milli - dateObs.milli));
                }
        } finally {
            DisplaySettings.setTimeMode(before);
        }
    }

    // Row 4
    private static void checkLightTime() {
        JHVTime t = new JHVTime("2026-04-25T00:00:00");
        Vec3 o = new Vec3(10, -20, 5);
        double r0 = Sun.MeanEarthDistance; // 1 au, in Rsun
        double v = 564 / (Sun.RadiusMeter / 1e3) / 1000; // 564 km/s in Rsun per ms
        Vec3 u = Identifier.unit(new Vec3(0.3, 0.8, -0.5));
        CometEphemeris.WorldPath away = (milli, frame) -> {
            double r = r0 + v * (milli - t.milli);
            return new Vec3(o.x + u.x * r, o.y + u.y * r, o.z + u.z * r);
        };
        long got = CometSight.emitted(away, t, o);
        double c = 1 / Sun.RadiusMilli; // Rsun per ms
        double want = t.milli - r0 / (c + v);
        expect(Math.abs(got - want) <= 1, String.format(Locale.ROOT, "light time on a path receding at 564 km/s from 1 au is t - R0/(c+v) (off by %.1f ms)", got - want));
    }

    // Row 5
    private static void checkVisibility() throws Exception {
        File file = DATA.resolve("20241224_194245_d4c2A.fts").toFile();
        if (!file.isFile()) {
            expect(false, "fixture " + file + " is readable");
            return;
        }
        URIView.SourceInfo info = FITSImage.readInfo(file, 0);
        FitsMetaData m = new FitsMetaData(new XMLMetaDataContainer(info.xml()), file.toURI());
        Region region = m.roiToRegion(0, 0, info.width(), info.height(), 1, 1);
        Identifier.Target target = new Identifier.Target("COR2", m.getViewpoint(), m.getWcsHeader(), region, m.getInnerRadius(), m.getOuterRadius());
        Position obs = target.observer();
        double d = obs.distance, inner = m.getInnerRadius(), outer = m.getOuterRadius();
        Vec3 o = ModelPlacement.toWorld(obs);
        Quat q = obs.toQuat();

        record Case(String what, Vec3 dir, double range, Identifier.Qualifier want) {}
        List<Case> cases = List.of(
                new Case("behind the Sun", Identifier.unit(new Vec3(0.001, 0, -1)), 2 * d, Identifier.Qualifier.BEHIND_SUN),
                new Case(String.format(Locale.ROOT, "%.2f Rsun from Sun centre, inside the occulter (%.2f)", (1 + inner) / 2, inner),
                        Probes.plane((1 + inner) / 2, 0).direction(obs), d, Identifier.Qualifier.OCCULTED),
                new Case(String.format(Locale.ROOT, "%.1f Rsun, past the outer radius (%.1f)", 1.5 * outer, outer),
                        Probes.plane(0, 1.5 * outer).direction(obs), d, Identifier.Qualifier.OUTSIDE_FIELD),
                new Case(String.format(Locale.ROOT, "%.1f Rsun, in the field", (inner + outer) / 2),
                        Probes.plane(-(inner + outer) / 2, 0).direction(obs), d, Identifier.Qualifier.NONE));
        for (Case k : cases) {
            Vec3 w = q.rotateInverseVector(new Vec3(k.dir().x * k.range(), k.dir().y * k.range(), k.dir().z * k.range()));
            Vec3 at = new Vec3(o.x + w.x, o.y + w.y, o.z + w.z);
            CometSight.Look look = CometSight.look((milli, frame) -> at, target, m.getObservatory(), null, false, DisplaySettings.TimeMode.Observer);
            expect(look.qualifier() == k.want(), "COR2 header, a comet " + k.what() + ": " + look.qualifier() + " (want " + k.want() + ")");
            if (k.want() == Identifier.Qualifier.NONE && look.sight() != null) {
                Vec2 plane = look.sight().planeRsun(d);
                expect(Math.abs(plane.x + (inner + outer) / 2) < 1e-9 && Math.abs(plane.y) < 1e-9,
                        String.format(Locale.ROOT, "and its sight crosses the plane of sky at (%.6f, %.6f) Rsun, the point built", plane.x, plane.y));
            }
        }
    }

    // Row 6
    private static void checkPadding() throws Exception {
        long t0 = new JHVTime("2026-04-25T00:00:00").milli, t1 = t0 + DAY;
        long[] w = CometEphemeris.window(t0, t1);
        CometEphemeris v = venus(w[0], w[1], HOUR);
        Position earth = Spice.getCarrington("EARTH", new JHVTime(t0));
        CometSight.Sight s = earth == null ? null : CometSight.sight(v::world, earth, null, true, DisplaySettings.TimeMode.Observer);
        expect(s != null && s.emitted() < t0, "a path fetched for [t0, t1] gives a sight at t0 with light time on (emitted "
                + (s == null ? "nowhere" : (t0 - s.emitted()) / 1000. + " s before t0") + ")");
    }

    // Row 7
    private static void checkObserverSource() {
        JHVTime t = new JHVTime("2026-04-25T00:00:00");
        Position earth = Sun.getEarth(t);
        Position atEarth = new Position(t, earth.distance, earth.lon, earth.lat);
        Position sta = Spice.getCarrington("STEREO AHEAD", t);
        expect(CometSight.observerSource("SOHO", atEarth, t).text.equals("observer: SOHO from JPL Horizons"), "a SOHO layer reports \"observer: SOHO from JPL Horizons\"");
        expect(CometSight.observerSource("SDO", atEarth, t) == CometSight.ObserverSource.SDO_AT_EARTH, "an SDO layer reports Earth, where Studio places SDO");
        expect(CometSight.observerSource("STEREO", atEarth, t).text.equals("observer: at Earth (header has no position keys?)"),
                "a non-SDO header exactly at Earth reports \"at Earth (header has no position keys?)\"");
        expect(sta != null && CometSight.observerSource("STEREO", sta, t) == CometSight.ObserverSource.HEADER, "a header elsewhere reports the header");
        Identifier.Target sohoLayer = new Identifier.Target("C3", atEarth, null, null, 0, Double.POSITIVE_INFINITY);
        CometSight.Look look = CometSight.look((milli, frame) -> new Vec3(0, 0, 100), sohoLayer, "SOHO", null, true, DisplaySettings.TimeMode.Observer);
        expect(look.sight() == null && !look.drawn(), "a SOHO layer with no SOHO path yet draws nothing rather than guess from its header");
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
