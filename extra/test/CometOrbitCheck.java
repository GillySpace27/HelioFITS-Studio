package org.helioviewer.jhv.layers;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import org.helioviewer.jhv.app.AppInit;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.astronomy.CometEphemeris;
import org.helioviewer.jhv.astronomy.Comets;
import org.helioviewer.jhv.astronomy.Spice;
import org.helioviewer.jhv.astronomy.Sun;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.JplHorizons;
import org.helioviewer.jhv.layers.grid.PlanetMarkers;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.opengl.BufVertex;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONObject;

/**
 * The comet layer's orbit and now-dot (comet3d plan, revision 2, section 3, CometOrbitCheck), with
 * SPICE.
 *
 * <p>Pins: (a) with Earth's states for a year as the path, the orbit vertex at t0 + 90 d built at
 * display time t0 is Earth at t0 + 90 d rotated into the frame at t0 (the Bodies.worldAt form), so
 * the orbit is turned with one matrix, at the display time; (b) the vertex at t0 is where the
 * grid's planets put Earth (PlanetMarkers.positions, following solar rotation); (c) the orbit
 * window rules, perihelion = 0 meaning none; (d) eight Hermite sub-chords per sample interval, the
 * display time inserted; (e) the line splits at the display time, past brighter than future, and
 * breaks where the path has no position; (f) the orbit's session fields round trip and an
 * unreadable span is clamped.
 */
public final class CometOrbitCheck {

    private static int failures;
    private static final long HOUR = 3_600_000L, DAY = 86_400_000L;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("comet-orbit-check");
        System.setProperty("user.home", home.toString());
        if (System.getProperty("user.timezone") == null)
            System.setProperty("user.timezone", "UTC");
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs();
        AppInit.loadSpice();

        checkFrame();
        checkWindow();
        checkTimes();
        checkSplit();
        checkSession();

        if (failures != 0) {
            System.out.println("CometOrbitCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("CometOrbitCheck: PASS");
    }

    /** Earth's heliocentric states from SPICE, daily, as a comet path (Spice.state returns Rsun and v/c). */
    private static CometEphemeris earth(long start, long end) throws Exception {
        int n = (int) ((end - start) / DAY) + 1;
        long[] t = new long[n];
        double[] km = new double[3 * n], kms = new double[3 * n];
        for (int i = 0; i < n; i++) {
            t[i] = start + i * DAY;
            double[] s = Spice.getState("SUN", "EARTH", "ECLIPJ2000", new JHVTime(t[i]));
            for (int k = 0; k < 3; k++) {
                km[3 * i + k] = s[k] / Sun.RadiusKMeterInv;
                kms[3 * i + k] = s[3 + k] * Sun.CLIGHT / 1000;
            }
        }
        return CometEphemeris.of("Earth", new JplHorizons.States(t, km, kms), "", 0);
    }

    // Rows (a) and (b)
    private static void checkFrame() throws Exception {
        JHVTime t0 = new JHVTime("2026-04-25T00:00:00");
        CometEphemeris e = earth(t0.milli - 180 * DAY, t0.milli + 185 * DAY);
        long[] times = CometLayer.orbitTimes(e.sampleTimes(), t0.milli);
        double[] xyz = e.worldAll(times, t0);
        long later = t0.milli + 90 * DAY;
        int iLater = Arrays.binarySearch(times, later), iNow = Arrays.binarySearch(times, t0.milli);
        if (xyz == null || iLater < 0 || iNow < 0) {
            expect(false, "the orbit has vertices at t0 and t0 + 90 d");
            return;
        }
        double[] j2000 = Spice.getPosition("SUN", "EARTH", "J2000", new JHVTime(later));
        double[] v = Spice.rotate("J2000", "SOLO_IAU_SUN_2009", t0.milli, j2000);
        Vec3 want = ModelPlacement.toWorld(ModelPlacement.carringtonPosition(t0, v[0], v[1], v[2]));
        double d = dist(xyz, iLater, want);
        expect(d <= 1e-3, String.format(Locale.ROOT, "the orbit vertex at t0 + 90 d, built at t0, is Earth then in the frame at t0 (off by %.2e Rsun)", d));

        Vec3 grid = null;
        for (PlanetMarkers.Marker m : PlanetMarkers.positions(t0, true))
            if ("Earth".equals(m.body().toString()))
                grid = m.position();
        double g = grid == null ? Double.POSITIVE_INFINITY : dist(xyz, iNow, grid);
        expect(g <= 1e-4, String.format(Locale.ROOT, "the vertex at t0 is where the grid's planets put Earth (off by %.2e Rsun)", g));
    }

    private static double dist(double[] xyz, int i, Vec3 p) {
        return Math.sqrt(Math.pow(xyz[3 * i] - p.x, 2) + Math.pow(xyz[3 * i + 1] - p.y, 2) + Math.pow(xyz[3 * i + 2] - p.z, 2));
    }

    // Row (c)
    private static void checkWindow() {
        long a = new JHVTime("2026-04-14T12:28:00").milli, b = new JHVTime("2026-05-11T22:36:00").milli;
        long tp = new JHVTime("2026-04-25T03:00:00").milli;
        long[] w = CometLayer.orbitWindow(a, b, tp, 30);
        expect(w[0] == new JHVTime("2026-03-26T00:00:00").milli && w[1] == new JHVTime("2026-05-26T00:00:00").milli,
                "perihelion in the movie: 30 days each side of it, widened to the movie, whole UTC days (" + new JHVTime(w[0]) + " to " + new JHVTime(w[1]) + ")");
        w = CometLayer.orbitWindow(a, b, 0, 30);
        expect(w[0] == new JHVTime("2026-03-15T00:00:00").milli && w[1] == new JHVTime("2026-06-11T00:00:00").milli,
                "perihelion 0 means none: the movie, 30 days each side (" + new JHVTime(w[0]) + " to " + new JHVTime(w[1]) + ")");
        long t = new JHVTime("2026-04-20T06:00:00").milli;
        w = CometLayer.orbitWindow(t, t, 0, 10);
        expect(w[0] == new JHVTime("2026-04-10T00:00:00").milli && w[1] == new JHVTime("2026-05-01T00:00:00").milli,
                "no movie: the display time, 10 days each side (" + new JHVTime(w[0]) + " to " + new JHVTime(w[1]) + ")");
        w = CometLayer.orbitWindow(a, b, tp + 5 * 365 * DAY, 30);
        expect(w[1] - w[0] == CometLayer.MAX_WINDOW && w[0] <= a && w[1] >= b,
                "a perihelion five years after the movie: three years, starting with the movie (" + new JHVTime(w[0]) + " to " + new JHVTime(w[1]) + ")");
        w = CometLayer.orbitWindow(a, b, tp - 5 * 365 * DAY, 30);
        expect(w[1] - w[0] == CometLayer.MAX_WINDOW && w[0] <= a && w[1] >= b,
                "and five years before: three years, ending with the movie (" + new JHVTime(w[0]) + " to " + new JHVTime(w[1]) + ")");
    }

    // Row (d)
    private static void checkTimes() {
        long[] samples = {0, 8 * HOUR, 16 * HOUR};
        long[] none = CometLayer.orbitTimes(samples, -HOUR);
        boolean even = none.length == 17;
        for (int i = 1; even && i < none.length; i++)
            even = none[i] - none[i - 1] == HOUR;
        expect(even, "two sample intervals give 8 equal sub-chords each, 17 vertices (" + none.length + ")");
        long now = 3 * HOUR + 30 * 60_000L;
        long[] split = CometLayer.orbitTimes(samples, now);
        expect(split.length == 18 && Arrays.binarySearch(split, now) == 4, "the display time is inserted where it falls (" + split.length + " vertices)");
        expect(CometLayer.orbitTimes(samples, 2 * HOUR).length == 17, "and not twice when it falls on a sub-chord");
    }

    // Row (e)
    private static void checkSplit() {
        long[] times = {0, 1, 2, 3, 4, 5, 6};
        double[] xyz = new double[3 * times.length];
        Arrays.fill(xyz, 1);
        xyz[3 * 5] = Double.NaN;
        BufVertex b = new BufVertex(32);
        CometLayer.putOrbit(b, times, xyz, 2, new int[]{240, 80, 80});
        ByteBuffer buf = b.toBuffer();
        int[] alpha = new int[b.getCount()];
        for (int i = 0; i < alpha.length; i++)
            alpha[i] = buf.get(i * 20 + 19) & 0xFF;
        int[] want = {0, 204, 204, 204, 0, 0, 89, 89, 89, 0, 0, 89, 0};
        expect(Arrays.equals(alpha, want), "the line splits at the display time, past 0.8 and future 0.35 alpha, and breaks where the path has no position "
                + Arrays.toString(alpha));
    }

    // Row (f)
    private static void checkSession() throws Exception {
        CometLayer layer = new CometLayer(new Comets.Comet("C/2025 R3", "", 0.5, 0), new int[]{1, 2, 3});
        layer.setShowOrbit(false);
        layer.setShowDot(false);
        layer.setOrbitSpanDays(45);
        JSONObject jo = new JSONObject();
        layer.serialize(jo);
        CometLayer back = (CometLayer) Class.forName(CometLayer.class.getName()).getConstructor(JSONObject.class).newInstance(new JSONObject(jo.toString()));
        expect(!back.showOrbit() && !back.showDot() && back.orbitSpanDays() == 45, "the orbit and dot switches and the span come back from a session (" + jo + ")");
        CometLayer old = new CometLayer(new JSONObject("{\"designation\":\"C/2025 R3\"}"));
        expect(old.showOrbit() && old.showDot() && old.orbitSpanDays() == 30, "a PR2 session without them gets orbit and dot on, 30 days");
        CometLayer big = new CometLayer(new JSONObject("{\"designation\":\"C/2025 R3\",\"orbitSpanDays\":100000}"));
        CometLayer zero = new CometLayer(new JSONObject("{\"designation\":\"C/2025 R3\",\"orbitSpanDays\":0}"));
        expect(big.orbitSpanDays() == 365 && zero.orbitSpanDays() == 1, "a span out of range is clamped to 1..365 days (" + big.orbitSpanDays() + ", " + zero.orbitSpanDays() + ")");
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }
}
