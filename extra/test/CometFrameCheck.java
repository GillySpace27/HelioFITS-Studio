package org.helioviewer.jhv.layers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.CometEphemeris;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.HelioFleet;
import org.helioviewer.jhv.io.JplHorizons;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The frame chain of the comet's 3D path, against JPL Horizons (comet3d plan, revision 2, section
 * 1.8, CometFrameCheck), with SPICE.
 *
 * <p>Pins, with Earth's Horizons state vectors around the June 2025 solstice in the J2000 ecliptic
 * (extra/test/data/horizons-earth-2025-06-21-ecliptic.txt) and in ICRF
 * (horizons-earth-2025-06-21-frame.txt): (1) SPICE's ECLIPJ2000 obliquity equals the one the
 * Horizons answer states; (2) the ecliptic vectors rotated about x by that angle are the ICRF
 * vectors, component by component (no atan2, so no branch cut near -y where Earth is in June);
 * (3) Horizons' Earth through {@code CometEphemeris.world} lands on SPICE's Earth in the world frame,
 * at a sample and between samples; (4) at a sample, {@code world} equals {@code ExternalEphemeris.at},
 * the chain spacecraft from Horizons already use, and {@code worldAll} equals {@code world}. A
 * missing fixture is a FAIL.
 */
public final class CometFrameCheck {

    private static int failures;

    private static final Path DATA = Path.of("extra/test/data");
    private static final JHVTime T = new JHVTime("2025-06-21T00:00:00"); // a sample of both fixtures
    private static final JHVTime MID = new JHVTime("2025-06-21T03:00:00"); // halfway between two samples

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("comet-frame-check");
        System.setProperty("user.home", home.toString());
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        AppInit.loadSpice();

        String ecliptic = fixture("horizons-earth-2025-06-21-ecliptic.txt"), frame = fixture("horizons-earth-2025-06-21-frame.txt");
        if (ecliptic != null) {
            double eps = checkObliquity(ecliptic);
            JplHorizons.States ecl = JplHorizons.parseStates(ecliptic);
            if (frame != null)
                checkRotation(ecl, JplHorizons.parseStates(frame), eps);
            checkEndToEnd(ecl);
            checkAgainstExternal(ecl);
        }

        if (failures != 0) {
            System.out.println("CometFrameCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("CometFrameCheck: PASS");
    }

    private static String fixture(String name) {
        try {
            return Files.readString(DATA.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            expect(false, "fixture extra/test/data/" + name + " is readable (" + e + ")");
            return null;
        }
    }

    // Row 1: returns SPICE's angle in radians.
    private static double checkObliquity(String ecliptic) throws Exception {
        Matcher m = Pattern.compile("IAU76 obliquity of ([0-9.]+) arcseconds").matcher(ecliptic);
        double stated = m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
        double eps = Math.acos(Spice.rotate("ECLIPJ2000", "J2000", T.milli, new double[]{0, 0, 1})[2]);
        double arcsec = Math.toDegrees(eps) * 3600;
        expect(Math.abs(arcsec - stated) <= 0.001,
                String.format(Locale.ROOT, "SPICE's ECLIPJ2000 obliquity %.4f arcsec equals the %s the Horizons answer states", arcsec, m.find(0) ? m.group(1) : "(no obliquity line)"));
        return eps;
    }

    // Row 2
    private static void checkRotation(JplHorizons.States ecl, JplHorizons.States icrf, double eps) {
        double c = Math.cos(eps), s = Math.sin(eps), worst = 0;
        boolean same = java.util.Arrays.equals(ecl.milli(), icrf.milli());
        for (int i = 0; same && i < ecl.milli().length; i++)
            for (double[][] pair : new double[][][]{{ecl.km(), icrf.km()}, {ecl.kms(), icrf.kms()}}) {
                double x = pair[0][3 * i], y = pair[0][3 * i + 1], z = pair[0][3 * i + 2];
                double[] rot = {x, c * y - s * z, s * y + c * z};
                double len = Math.sqrt(x * x + y * y + z * z);
                for (int k = 0; k < 3; k++)
                    worst = Math.max(worst, Math.abs(rot[k] - pair[1][3 * i + k]) / len);
            }
        expect(same && worst <= 1e-6, String.format(Locale.ROOT,
                "the ecliptic positions and velocities rotated about x by SPICE's angle are Horizons' ICRF ones, per component (worst %.2e of the length)", worst));
    }

    // Row 3
    private static void checkEndToEnd(JplHorizons.States ecl) throws Exception {
        CometEphemeris e = CometEphemeris.of("Earth", ecl, "", 0);
        for (JHVTime t : new JHVTime[]{T, MID}) {
            Vec3 got = e.world(t.milli, t);
            double[] c = Spice.getPosition("SUN", "EARTH", "SOLO_IAU_SUN_2009", t);
            Vec3 want = ModelPlacement.toWorld(ModelPlacement.carringtonPosition(t, c[0], c[1], c[2]));
            double d = got == null ? Double.POSITIVE_INFINITY : dist(got, want);
            expect(d <= 1e-3, String.format(Locale.ROOT, "Horizons' Earth through world(t, t) at %s lands on SPICE's Earth (residual %.3e Rsun)", t, d));
        }
    }

    // Row 4
    private static void checkAgainstExternal(JplHorizons.States ecl) throws Exception {
        CometEphemeris e = CometEphemeris.of("Earth", ecl, "", 0);
        Vec3 got = e.world(T.milli, T);
        Vec3 want = ModelPlacement.toWorld(ExternalEphemeris.at(new HelioFleet.Samples(ecl.milli(), ecl.km(), false), T));
        double rel = got == null ? Double.POSITIVE_INFINITY : dist(got, want) / want.length();
        expect(rel <= 1e-9, String.format(Locale.ROOT, "at a sample, world equals ExternalEphemeris.at (relative %.2e)", rel));

        long[] times = {T.milli, MID.milli, ecl.milli()[0] - 1};
        double[] all = e.worldAll(times, T);
        boolean ok = all != null && Double.isNaN(all[6]);
        for (int i = 0; ok && i < 2; i++) {
            Vec3 one = e.world(times[i], T);
            ok = one != null && one.x == all[3 * i] && one.y == all[3 * i + 1] && one.z == all[3 * i + 2];
        }
        expect(ok, "worldAll with one rotation equals world point by point, and is NaN outside the samples");
    }

    private static double dist(Vec3 a, Vec3 b) {
        double x = a.x - b.x, y = a.y - b.y, z = a.z - b.z;
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
