package org.helioviewer.jhv.astronomy;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.helioviewer.jhv.io.JplHorizons;
import org.helioviewer.jhv.time.JHVTime;

/**
 * The comet's 3D ephemeris without the network or SPICE (comet3d plan, revision 2, section 1.8): the
 * rows that need no captured comet fixture.
 *
 * <p>Pins: (3) Hermite returns the samples exactly at the nodes; (4) on a synthetic Kepler parabola
 * sampled on the {@code segments} grid, the Hermite error stays inside the 1e-3 Rsun budget and no
 * more than 20 % above the plan's simulated values; (6) {@code segments} gives the pieces and steps
 * extra/tools/capture_horizons_fixtures.py segments() gives; (7) no perihelion (0, as Comets stores
 * a missing one) or no q gives one request at the 2000-interval cap; (8) {@code fetch} pads the
 * window by a day each side, and its URLs are the ones the capture script prints; (9) the cache:
 * round trip, coverage, expiry, Refresh, a corrupt file, no .part left; (12) null outside the
 * samples; the guard resamples an orbit tighter than the catalogue's; and the synthetic parts of
 * (1) column order, (2) refusals and (11) the source tag.
 *
 * <p>Against the C/2025 R3 (PANSTARRS) answers captured with extra/tools/capture_horizons_fixtures.py
 * (extra/test/data/horizons-*-c_2025_r3-*, sbdb-c_2025_r3.json): (1) the VEC_TABLE 2 header names
 * the columns the parser assumes, and the tables parse to their printed times and values; (5) every
 * other sample, dropped and Hermite-interpolated back, lands inside the plan's error bound; (10)
 * every request Studio builds is the URL in the .url sidecar; (11) the source tag is the target
 * line's; and both forms of a numbered periodic designation resolve. A missing fixture is a FAIL.
 */
public final class CometEphemerisCheck {

    private static int failures;

    private static final long MINUTE = 60_000L, DAY = 86_400_000L;
    private static final double RS = 6.957e8, GM = 1.32712440018e20; // as the plan's hermite.py (Appendix A)
    private static final double TILT = Math.toRadians(30); // orbit plane tilt, so all three components are exercised

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("comet-ephemeris-check");
        System.setProperty("user.home", home.toString());
        Path cache = CometEphemeris.cacheDir();
        if (!cache.startsWith(home)) { // HFS_HOME or hfs.home set: never write into someone's real folder
            System.out.println("  FAIL the cache folder " + cache + " is outside the temporary home; unset HFS_HOME");
            System.exit(1);
        }

        checkNodes();
        checkParabola();
        checkSegments();
        checkNoPerihelion();
        checkPaddingAndUrls();
        checkCache(cache);
        checkOutside();
        checkGuard();
        checkParseSynthetic();
        checkRefusals();
        checkSourceTag();

        checkFixtureParse();
        checkFixtureHermite();
        checkFixtureUrls();
        checkFixtureSourceTag();
        checkProbes();

        if (failures != 0) {
            System.out.println("CometEphemerisCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("CometEphemerisCheck: PASS");
    }

    // The plan's hermite.py state(): a parabola of perihelion q (m), t s from perihelion, here tilted out of the ecliptic.
    private static double[] state(double q, double t) {
        double n = Math.sqrt(GM / (2 * q * q * q)), m = n * t;
        double w = (3 * m + Math.sqrt(9 * m * m + 4)) / 2;
        w = w > 0 ? Math.pow(w, 1. / 3) : -Math.pow(-w, 1. / 3);
        double d = w - 1 / w, dd = n / (1 + d * d);
        double x = q * (1 - d * d), y = 2 * q * d, vx = -2 * q * d * dd, vy = 2 * q * dd;
        double c = Math.cos(TILT), s = Math.sin(TILT);
        return new double[]{x / 1000, y * c / 1000, y * s / 1000, vx / 1000, vy * c / 1000, vy * s / 1000}; // km, km/s
    }

    private static JplHorizons.States sample(double qRsun, long tp, long start, long end, int step) {
        int n = (int) ((end - start) / (step * MINUTE)) + 1;
        long[] t = new long[n];
        double[] km = new double[3 * n], kms = new double[3 * n];
        for (int i = 0; i < n; i++) {
            t[i] = start + i * step * MINUTE;
            double[] st = state(qRsun * RS, (t[i] - tp) / 1000.);
            System.arraycopy(st, 0, km, 3 * i, 3);
            System.arraycopy(st, 3, kms, 3 * i, 3);
        }
        return new JplHorizons.States(t, km, kms);
    }

    private static CometEphemeris onGrid(double qRsun, long tp, List<CometEphemeris.Segment> segs) {
        List<JplHorizons.States> parts = new ArrayList<>();
        for (CometEphemeris.Segment s : segs)
            parts.add(sample(qRsun, tp, s.start(), s.end(), s.stepMinutes()));
        return CometEphemeris.of("synthetic", CometEphemeris.merge(parts), "", 0);
    }

    private static final long TP = new JHVTime("2026-04-25T12:34:56").milli;

    // Row 3
    private static void checkNodes() {
        JplHorizons.States s = sample(1.2, TP, TP - 30 * 9 * MINUTE + 17_000, TP + 30 * 9 * MINUTE + 17_000, 9);
        CometEphemeris e = CometEphemeris.of("nodes", s, "", 0);
        double worst = 0;
        for (int i = 0; i < s.milli().length; i++) {
            double[] p = e.eclipticKm(s.milli()[i]);
            for (int k = 0; k < 3; k++)
                worst = Math.max(worst, p == null ? Double.POSITIVE_INFINITY : Math.abs(p[k] - s.km()[3 * i + k]));
        }
        expect(worst <= 1e-6, "Hermite returns every sample exactly at its node, first and last included (worst " + worst + " km)");
    }

    // Row 4: q (Rsun), window (days from tp), the plan's simulated max Hermite error (table 1.4, computed there with policy2.py).
    // One-sided: the script's anchoring puts the coarse pieces' last step at the dense edge, which halves the plan's
    // error for q = 1.2, -30..+30 d (1.56e-4 against 3.0e-4) and q = 2, -90..+90 d (1.54e-4 against 3.2e-4).
    private static final double[][] TABLE = {
            {1.2, -2, 2, 5.2e-5}, {1.2, -30, 30, 3.0e-4}, {1.2, -182, 0, 1.7e-4}, {1.2, -730, 730, 5.1e-4},
            {2, -90, 90, 3.2e-4}, {5, 1, 30, 1.4e-4}, {20, -30, 30, 9.5e-5}, {100, -90, 90, 9.0e-6}};

    private static void checkParabola() {
        for (double[] row : TABLE) {
            double q = row[0];
            List<CometEphemeris.Segment> segs = CometEphemeris.segments(q, TP, TP + (long) (row[1] * DAY), TP + (long) (row[2] * DAY));
            CometEphemeris e = onGrid(q, TP, segs);
            long[] t = e.sampleTimes();
            double worst = 0;
            for (int i = 0; i + 1 < t.length; i++)
                for (int j = 1; j < 8; j++) {
                    long at = t[i] + (t[i + 1] - t[i]) * j / 8;
                    double[] truth = state(q * RS, (at - TP) / 1000.), p = e.eclipticKm(at);
                    double d = p == null ? Double.POSITIVE_INFINITY : Math.sqrt(sq(p[0] - truth[0]) + sq(p[1] - truth[1]) + sq(p[2] - truth[2]));
                    worst = Math.max(worst, d * 1000 / RS);
                }
            String what = String.format(Locale.ROOT, "parabola q = %s Rsun, %+.0f..%+.0f d: max Hermite error %.2e Rsun", q, row[1], row[2], worst);
            expect(worst < 1e-3, what + " is inside the 1e-3 Rsun budget");
            expect(worst <= 1.2 * row[3], what + " is at most 20 % above the plan's " + row[3]);
        }
    }

    private static double sq(double x) {
        return x * x;
    }

    // Row 6: extra/tools/capture_horizons_fixtures.py segments() for the same rows, tp 2026-04-25 12:34:56 UTC (printed with
    // a scratch harness importing it; the plan's table 1.4 predates the script's anchoring, so its after-steps differ).
    private static final int[][] STEPS = {{9}, {670, 9, 672}, {670, 9}, {670, 9, 672}, {653, 17, 654}, {314, 624}, {540, 317, 573}, {1440, 1440, 1440}};

    private static void checkSegments() {
        for (int r = 0; r < TABLE.length; r++) {
            double[] row = TABLE[r];
            List<CometEphemeris.Segment> segs = CometEphemeris.segments(row[0], TP, TP + (long) (row[1] * DAY), TP + (long) (row[2] * DAY));
            int[] steps = segs.stream().mapToInt(CometEphemeris.Segment::stepMinutes).toArray();
            expect(Arrays.equals(steps, STEPS[r]), String.format(Locale.ROOT, "segments q = %s, %+.0f..%+.0f d: steps %s (script: %s)",
                    row[0], row[1], row[2], Arrays.toString(steps), Arrays.toString(STEPS[r])));
        }
        // The q = 1.2, -30..+30 d pieces, as the script prints them.
        List<CometEphemeris.Segment> segs = CometEphemeris.segments(1.2, TP, TP - 30 * DAY, TP + 30 * DAY);
        List<CometEphemeris.Segment> want = List.of(
                new CometEphemeris.Segment(t("2026-03-26T03:24:00"), t("2026-04-23T12:34:00"), 670),
                new CometEphemeris.Segment(t("2026-04-23T12:34:00"), t("2026-04-27T12:43:00"), 9),
                new CometEphemeris.Segment(t("2026-04-27T12:43:00"), t("2026-05-25T12:43:00"), 672));
        expect(segs.equals(want), "segments q = 1.2, -30..+30 d: the script's piece bounds, neighbours sharing their boundary (got " + segs + ")");
        expect(CometEphemeris.stepMinutes(1.2) == 9 && CometEphemeris.stepMinutes(0.1) == 2 && CometEphemeris.stepMinutes(1e4) == 1440,
                "stepMinutes: floor(7.5 r^1.25) clamped to [2, 1440]");
    }

    private static long t(String iso) {
        return new JHVTime(iso).milli;
    }

    // Row 7
    private static void checkNoPerihelion() {
        long start = t("2026-04-20T00:00:30"), end = t("2026-04-30T00:00:00");
        long lo = t("2026-04-20T00:00:00"), minutes = (end - lo) / MINUTE;
        int step = (int) Math.ceilDiv(minutes, JplHorizons.MAX_STEPS);
        List<CometEphemeris.Segment> want = List.of(new CometEphemeris.Segment(lo, lo + step * MINUTE * Math.ceilDiv(minutes, step), step));
        List<CometEphemeris.Segment> zero = CometEphemeris.segments(1.2, 0, start, end);
        List<CometEphemeris.Segment> nan = CometEphemeris.segments(Double.NaN, TP, start, end);
        expect(zero.equals(want), "perihelion 0 (Comets' missing value) gives one request at the cap, not a window from 1970 (got " + zero + ")");
        expect(nan.equals(want), "q NaN gives one request at the cap (got " + nan + ")");
        expect((want.getFirst().end() - want.getFirst().start()) / MINUTE / step <= JplHorizons.MAX_STEPS, "that request has at most 2000 intervals");
    }

    // A stand-in for Horizons: answers a states URL with a VEC_TABLE 2 CSV table of the tilted parabola, and records what it was asked.
    private static final class Fake implements CometEphemeris.Source {
        final double qRsun;
        final long tp;
        final List<String> asked = new ArrayList<>();
        static final DateTimeFormatter IN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        Fake(double qRsun, long tp) {
            this.qRsun = qRsun;
            this.tp = tp;
        }

        @Override
        public String result(String url) {
            asked.add(url);
            Map<String, String> p = params(url);
            long start = LocalDateTime.parse(unquote(p.get("START_TIME")), IN).toInstant(ZoneOffset.UTC).toEpochMilli();
            long stop = LocalDateTime.parse(unquote(p.get("STOP_TIME")), IN).toInstant(ZoneOffset.UTC).toEpochMilli();
            long step = Long.parseLong(unquote(p.get("STEP_SIZE")).replace(" m", "")) * MINUTE;
            StringBuilder b = new StringBuilder("Target body name: Synthetic (fake)    {source: JPL#K42}\n")
                    .append("Center body name: Sun (10)    {source: DE441}\n$$SOE\n");
            for (long at = start; at <= stop; at += step) {
                double[] s = state(qRsun * RS, (at - tp) / 1000.);
                b.append(String.format(Locale.ROOT, "%.9f, A.D. date, %.15E, %.15E, %.15E, %.15E, %.15E, %.15E,%n",
                        at / 864e5 + 2440587.5, s[0], s[1], s[2], s[3], s[4], s[5]));
            }
            return b.append("$$EOE\n").toString();
        }
    }

    private static Map<String, String> params(String url) {
        Map<String, String> m = new HashMap<>();
        for (String kv : url.substring(url.indexOf('?') + 1).split("&")) {
            int i = kv.indexOf('=');
            m.put(kv.substring(0, i), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String unquote(String s) {
        return s.startsWith("'") && s.endsWith("'") ? s.substring(1, s.length() - 1) : s;
    }

    // Row 8, and the URL part of row 10 that needs no fixture.
    private static void checkPaddingAndUrls() throws IOException {
        long start = t("2026-04-20T00:00:00"), end = t("2026-04-30T00:00:00");
        Fake none = new Fake(30, TP);
        CometEphemeris.fetch(new Comets.Comet("C/2099 Z1", "", 30 / Sun.MeanEarthDistance, 0), start, end, true, start, none);
        Map<String, String> p = params(none.asked.getFirst());
        expect(none.asked.size() == 1 && "'2026-04-19 00:00:00'".equals(p.get("START_TIME")),
                "fetch with no perihelion asks from exactly start - 1 d (asked " + p.get("START_TIME") + ")");
        long stop = LocalDateTime.parse(unquote(p.get("STOP_TIME")), Fake.IN).toInstant(ZoneOffset.UTC).toEpochMilli();
        expect(stop >= end + DAY && stop < end + DAY + Long.parseLong(unquote(p.get("STEP_SIZE")).replace(" m", "")) * MINUTE,
                "and to the first whole step at or after end + 1 d (asked " + p.get("STOP_TIME") + ")");

        // The capture script's dry run, verbatim:
        //   python3 extra/tools/capture_horizons_fixtures.py --des "C/2026 A1" --start 2026-04-20 --end 2026-04-30 \
        //       --dry-run --q-au 0.499 --tp-jd 2461150.5
        // tp there is JD 2461150.5 TDB less 69.184 s.
        long tp = Math.round((2461150.5 - 2440587.5) * 864e5) - 69_184;
        String head = "https://ssd.jpl.nasa.gov/api/horizons.api?format=json&COMMAND=";
        String mid = "&EPHEM_TYPE=VECTORS&CENTER=%27500%4010%27&REF_PLANE=ECLIPTIC&REF_SYSTEM=ICRF&OUT_UNITS=KM-S&VEC_TABLE=2&VEC_CORR=NONE&CSV_FORMAT=YES&TIME_TYPE=UT&OBJ_DATA=NO&START_TIME=";
        String comet = "%27DES%3DC%2F2026+A1%3B+CAP%3B+NOFRAG%3B%27";
        List<String> want = List.of(
                head + comet + mid + "%272026-04-19+00%3A00%3A00%27&STOP_TIME=%272026-04-22+00%3A00%3A00%27&STEP_SIZE=%271440+m%27",
                head + comet + mid + "%272026-04-22+00%3A00%3A00%27&STOP_TIME=%272026-05-01+00%3A00%3A00%27&STEP_SIZE=%271440+m%27");
        Fake a1 = new Fake(0.499 * 1.495978707e11 / RS, tp);
        CometEphemeris.fetch(new Comets.Comet("C/2026 A1", "", 0.499, tp), start, end, true, start, a1);
        expect(a1.asked.equals(want), "fetch pads the window and asks byte for byte what the capture script prints for C/2026 A1 (asked " + a1.asked + ")");

        Fake soho = new Fake(215, tp);
        CometEphemeris.fetchBody("-21", "SOHO", start, end, true, start, soho);
        String sohoWant = head + "%27-21%27" + mid + "%272026-04-19+00%3A00%3A00%27&STOP_TIME=%272026-05-01+00%3A00%3A00%27&STEP_SIZE=%2760+m%27";
        expect(soho.asked.equals(List.of(sohoWant)), "fetchBody pads and asks what the script prints for SOHO (asked " + soho.asked + ")");

        String earth = JplHorizons.statesUrl("399", t("2025-06-20T00:00:00"), t("2025-06-22T00:00:00"), 360);
        expect(earth.equals(head + "%27399%27" + mid + "%272025-06-20+00%3A00%3A00%27&STOP_TIME=%272025-06-22+00%3A00%3A00%27&STEP_SIZE=%27360+m%27"),
                "statesUrl for Earth equals the script's horizons-earth-2025-06-21-ecliptic URL");
    }

    // Row 9
    private static void checkCache(Path dir) throws IOException {
        long now = t("2026-04-25T00:00:00");
        long start = t("2026-04-24T00:00:00"), end = t("2026-04-26T00:00:00");
        Comets.Comet c = new Comets.Comet("C/2026 Q9", "Check", 5 / Sun.MeanEarthDistance, TP);
        Fake f = new Fake(5, TP);

        CometEphemeris first = CometEphemeris.fetch(c, start, end, false, now, f);
        int asked = f.asked.size();
        List<Path> files = files(dir);
        CometEphemeris again = CometEphemeris.fetch(c, start, end, false, now + 1000, f);
        expect(asked > 0 && f.asked.size() == asked, "cache: the same window again asks Horizons nothing (" + (f.asked.size() - asked) + " requests)");
        expect(Arrays.equals(first.sampleTimes(), again.sampleTimes()) && Arrays.equals(first.eclipticKm(TP), again.eclipticKm(TP))
                && "{source: JPL#K42}".equals(again.sourceTag()) && again.fetchedAt() == now, "cache: round trip keeps samples, positions, source tag and fetch time");
        expect(files.size() == 1 && files.getFirst().getFileName().toString().equals(CometEphemeris.fileName("C_2026_Q9", start - DAY, end + DAY, now)),
                "cache: one file, named <slug>_<startDay>_<endDay>_<fetched>_v2.json (got " + files + ")");

        asked = f.asked.size();
        CometEphemeris.fetch(c, start - 3 * DAY, end, false, now, f);
        expect(f.asked.size() > asked, "cache: a narrower file is not used for a wider need");

        // Same days in the name, but the window starts six hours too late: the content decides, not the name.
        Comets.Comet coarse = new Comets.Comet("C/2026 Q8", "", 5 / Sun.MeanEarthDistance, 0);
        Fake fc = new Fake(5, TP);
        CometEphemeris.fetch(coarse, t("2026-04-24T12:00:00"), end, false, now, fc);
        asked = fc.asked.size();
        CometEphemeris.fetch(coarse, t("2026-04-24T06:00:00"), end, false, now, fc);
        expect(fc.asked.size() > asked, "cache: a file whose name days cover the need but whose window does not is not used");

        // Perihelion within a year: a file 8 days old is passed over. Far from perihelion it is still used.
        asked = f.asked.size();
        CometEphemeris.fetch(c, start, end, false, now + 8 * DAY, f);
        expect(f.asked.size() > asked, "cache: a file 8 days old is not used while perihelion is recent");
        Comets.Comet old = new Comets.Comet("C/2024 Q9", "Check", 5 / Sun.MeanEarthDistance, TP - 2 * 365 * DAY);
        Fake fo = new Fake(5, TP - 2 * 365 * DAY);
        CometEphemeris.fetch(old, start, end, true, now, fo);
        int oldAsked = fo.asked.size();
        CometEphemeris.fetch(old, start, end, false, now + 8 * DAY, fo);
        expect(fo.asked.size() == oldAsked, "cache: a file 8 days old is used when perihelion is two years away");

        // Refresh: a second file, the first left as it was.
        Path keep = files.getFirst();
        byte[] keepBytes = Files.readAllBytes(keep);
        int n = files(dir).size();
        asked = f.asked.size();
        CometEphemeris.fetch(c, start, end, true, now, f); // same second as the first file: must not overwrite it
        expect(f.asked.size() > asked && files(dir).size() == n + 1, "cache: Refresh asks again and writes a second file");
        expect(Files.exists(keep) && Arrays.equals(keepBytes, Files.readAllBytes(keep)), "cache: Refresh leaves the earlier file as it was");

        // A corrupt file that would win on name (newest stamp, covering days) is skipped and stays.
        Path corrupt = dir.resolve(CometEphemeris.fileName("C_2026_Q9", start - DAY, end + DAY, now + 9 * DAY));
        Files.writeString(corrupt, "{not json", StandardCharsets.UTF_8);
        asked = f.asked.size();
        CometEphemeris hit = CometEphemeris.fetch(c, start, end, false, now + 1000, f);
        expect(f.asked.size() == asked && hit.first() <= start - DAY, "cache: a corrupt file is skipped and a good one used");
        expect(Files.exists(corrupt), "cache: the corrupt file is left where it was");

        try (Stream<Path> s = Files.list(dir)) {
            expect(s.noneMatch(p -> p.toString().endsWith(".part")), "cache: no .part file remains");
        }
    }

    private static List<Path> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("C_2026_Q9_") && p.toString().endsWith(".json")).sorted().toList();
        }
    }

    // Row 12
    private static void checkOutside() {
        JplHorizons.States s = sample(5, TP, TP - 10 * 60 * MINUTE, TP + 10 * 60 * MINUTE, 60);
        CometEphemeris e = CometEphemeris.of("outside", s, "", 0);
        expect(e.eclipticKm(e.first() - 1) == null && e.eclipticKm(e.last() + 1) == null, "outside [first, last]: null, not the end sample");
        expect(e.eclipticKm(e.first()) != null && e.eclipticKm(e.last()) != null, "at first and last: a position");
    }

    // Plan 1.4, a posteriori guard: a catalogue q twice the real one gives a grid too coarse; one resample at the measured minimum.
    private static void checkGuard() throws IOException {
        long start = TP - DAY, end = TP + DAY;
        Fake f = new Fake(1.2, TP);
        CometEphemeris e = CometEphemeris.fetch(new Comets.Comet("C/2026 G1", "", 2.4 / Sun.MeanEarthDistance, TP), start, end, false, start, f);
        String first = f.asked.isEmpty() ? "" : params(f.asked.getFirst()).get("STEP_SIZE"), second = f.asked.size() < 2 ? "" : params(f.asked.get(1)).get("STEP_SIZE");
        expect(f.asked.size() == 2 && minutes(second) < minutes(first) && CometEphemeris.RESAMPLED.equals(e.note()),
                "guard: a minimum below 0.9 q refetches once on a finer grid for the measured q and says so (steps " + first + " then " + second
                        + ", min " + String.format(Locale.ROOT, "%.3f", e.minDistanceRsun()) + " Rsun, note \"" + e.note() + "\")");
        Fake g = new Fake(2.4, TP);
        CometEphemeris.fetch(new Comets.Comet("C/2026 G2", "", 2.4 / Sun.MeanEarthDistance, TP), start, end, false, start, g);
        expect(g.asked.size() == 1, "guard: an orbit as the catalogue says is fetched once");
    }

    private static int minutes(String stepSize) {
        return stepSize.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(unquote(stepSize).replace(" m", ""));
    }

    // Row 1, synthetic part: the column order the parser assumes (JDUT, date, X, Y, Z, VX, VY, VZ).
    private static void checkParseSynthetic() throws IOException {
        String text = "$$SOE\n"
                + "2461150.500000000, A.D. 2026-Apr-20 00:00:00.0000, 1.0E+06, 2.0E+06, 3.0E+06, 4.0E+01, 5.0E+01, 6.0E+01,\n"
                + "2461150.750000000, A.D. 2026-Apr-20 06:00:00.0000, 7.0E+06, 8.0E+06, 9.0E+06, 1.0E+01, 1.1E+01, 1.2E+01,\n"
                + "$$EOE\n";
        JplHorizons.States s = JplHorizons.parseStates(text);
        expect(Arrays.equals(s.milli(), new long[]{t("2026-04-20T00:00:00"), t("2026-04-20T06:00:00")}), "parseStates: JDUT to UT milliseconds");
        expect(Arrays.equals(s.km(), new double[]{1e6, 2e6, 3e6, 7e6, 8e6, 9e6}) && Arrays.equals(s.kms(), new double[]{40, 50, 60, 10, 11, 12}),
                "parseStates: X, Y, Z from columns 3-5 and VX, VY, VZ from 6-8");
        String ulysses = fixture("horizons-ulysses-2005-06-01.txt");
        if (ulysses != null)
            expect(threw(ulysses) != null, "parseStates refuses a position-only table (the VEC_TABLE 1 Ulysses fixture): " + threw(ulysses));
    }

    // Row 2. No captured refusal exists (both probe forms resolved), so these are hand-made strings in Horizons'
    // wording as ExternalEphemerisCheck has it; they make no claim about a fixture.
    private static void checkRefusals() {
        String noEphem = "API VERSION: 1.2\nNo ephemeris for target \"Synthetic\" prior to A.D. 1600-JAN-01\n";
        String noMatch = "API VERSION: 1.2\n    No matches found.\n";
        String a = threw(noEphem), b = threw(noMatch);
        expect(a != null && a.contains("No ephemeris for target \"Synthetic\" prior to A.D. 1600-JAN-01"), "hand-made \"No ephemeris\" answer: an IOException with that line (" + a + ")");
        expect(b != null && b.contains("No matches found."), "hand-made \"No matches\" answer: an IOException with that line (" + b + ")");
    }

    private static String threw(String text) {
        try {
            JplHorizons.parseStates(text);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    // Row 11, synthetic part, and the Ulysses fixture's tag.
    private static void checkSourceTag() throws IOException {
        String text = "Target body name: Synthetic (fake)    {source: JPL#K42}\nCenter body name: Sun (10)    {source: DE441}\n";
        expect("{source: JPL#K42}".equals(JplHorizons.sourceTag(text)), "sourceTag: the target line's tag, not the centre's (" + JplHorizons.sourceTag(text) + ")");
        expect(JplHorizons.sourceTag("no header\n").isEmpty(), "sourceTag: \"\" when there is no target line");
        String ulysses = fixture("horizons-ulysses-2005-06-01.txt");
        if (ulysses != null)
            expect("{source: ulysses}".equals(JplHorizons.sourceTag(ulysses)), "sourceTag of the Ulysses fixture: {source: ulysses}");
    }

    // Fixtures

    private static final Path DATA = Path.of("extra/test/data");

    /** The fixture's text, or null after a FAIL line when it is missing. */
    private static String fixture(String name) {
        try {
            return Files.readString(DATA.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            expect(false, "fixture extra/test/data/" + name + " is readable (" + e + ")");
            return null;
        }
    }

    private static final String COMET = "horizons-comet-c_2025_r3-states-";
    private static final String[] COMET_PIECES = {COMET + "0.txt", COMET + "1.txt", COMET + "2.txt"};

    // Row 1
    private static void checkFixtureParse() throws IOException {
        String t0 = fixture(COMET_PIECES[0]);
        if (t0 != null) {
            String header = t0.lines().takeWhile(l -> !l.startsWith("$$SOE")).filter(l -> l.strip().startsWith("JDUT")).findFirst().orElse("");
            List<String> names = Arrays.stream(header.split(",")).map(String::strip).toList();
            expect(names.equals(List.of("JDUT", "Calendar Date (UT )", "X", "Y", "Z", "VX", "VY", "VZ")),
                    "the VEC_TABLE 2 header names JDUT, date, X, Y, Z, VX, VY, VZ in the order parseStates reads (" + names + ")");
            JplHorizons.States s = JplHorizons.parseStates(t0);
            expect(Arrays.equals(Arrays.copyOf(s.km(), 3), new double[]{-2.421163857753373E+07, -5.969670750236343E+07, 4.537612401032251E+07})
                            && Arrays.equals(Arrays.copyOf(s.kms(), 3), new double[]{-4.111603352657935E+01, 3.336297652575228E+00, -4.083465572975827E+01}),
                    "comet piece 0, first row: X, Y, Z and VX, VY, VZ are the printed values");
        }
        String[][] want = { // file, rows, first UT, last UT (the fixtures' calendar column)
                {COMET_PIECES[0], "6", "2026-04-12T21:26:00", "2026-04-17T21:26:00"},
                {COMET_PIECES[1], "6", "2026-04-17T21:26:00", "2026-04-22T21:26:00"},
                {COMET_PIECES[2], "22", "2026-04-22T21:26:00", "2026-05-13T21:26:00"},
                {"horizons-soho-c_2025_r3-states.txt", "707", "2026-04-13T12:28:29", "2026-05-12T22:28:29"},
                {"horizons-earth-2025-06-21-ecliptic.txt", "9", "2025-06-20T00:00:00", "2025-06-22T00:00:00"}};
        for (String[] w : want) {
            String text = fixture(w[0]);
            if (text == null)
                continue;
            JplHorizons.States s = JplHorizons.parseStates(text);
            long[] m = s.milli();
            expect(m.length == Integer.parseInt(w[1]) && m[0] == t(w[2]) && m[m.length - 1] == t(w[3]),
                    w[0] + ": " + m.length + " states from " + w[2] + " to " + w[3] + " UT");
        }
    }

    // Row 5: every other sample of the comet, dropped and interpolated back from its neighbours.
    private static void checkFixtureHermite() throws IOException {
        List<String> texts = new ArrayList<>();
        for (String f : COMET_PIECES) {
            String text = fixture(f);
            if (text == null)
                return;
            texts.add(text);
        }
        CometEphemeris all = CometEphemeris.parse("C/2025 R3", texts, 0, Double.NaN);
        long[] t = all.sampleTimes();
        int keep = (t.length + 1) / 2;
        long[] kt = new long[keep];
        double[] km = new double[3 * keep], kms = new double[3 * keep];
        JplHorizons.States full = CometEphemeris.merge(texts.stream().map(x -> {
            try {
                return JplHorizons.parseStates(x);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList());
        for (int i = 0; i < keep; i++) {
            kt[i] = t[2 * i];
            System.arraycopy(full.km(), 6 * i, km, 3 * i, 3);
            System.arraycopy(full.kms(), 6 * i, kms, 3 * i, 3);
        }
        CometEphemeris half = CometEphemeris.of("half", new JplHorizons.States(kt, km, kms), "", 0);
        int bad = 0, n = 0;
        double worst = 0;
        for (int i = 1; i < t.length - 1; i += 2) {
            double[] p = half.eclipticKm(t[i]);
            double r = Math.sqrt(sq(full.km()[3 * i]) + sq(full.km()[3 * i + 1]) + sq(full.km()[3 * i + 2])) * 1000 / RS;
            double h2 = 2 * (t[i + 1] - t[i]) / (double) MINUTE; // the doubled step, minutes
            double bound = Math.max(1e-6, 16 * 1.95e-8 * Math.pow(h2, 4) / Math.pow(r, 5)); // C = 1.95e-8 Rsun min^-4 Rsun^5, plan 1.4
            double err = p == null ? Double.POSITIVE_INFINITY
                    : Math.sqrt(sq(p[0] - full.km()[3 * i]) + sq(p[1] - full.km()[3 * i + 1]) + sq(p[2] - full.km()[3 * i + 2])) * 1000 / RS;
            worst = Math.max(worst, err / bound);
            n++;
            if (err > bound)
                bad++;
        }
        expect(n > 10 && bad == 0, String.format(Locale.ROOT, "C/2025 R3: %d samples dropped and interpolated back, each inside max(1e-6 Rsun, 16 C (2h)^4 / r^5) (worst %.3f of its bound)", n, worst));
    }

    // Row 10: the requests Studio builds are the URLs the capture asked, byte for byte.
    private static void checkFixtureUrls() throws IOException {
        String sbdb = fixture("sbdb-c_2025_r3.json");
        if (sbdb == null)
            return;
        double qAu = Double.NaN, tpJd = Double.NaN;
        org.json.JSONArray el = new org.json.JSONObject(sbdb).getJSONObject("orbit").getJSONArray("elements");
        for (int i = 0; i < el.length(); i++) {
            org.json.JSONObject e = el.getJSONObject(i);
            if ("q".equals(e.getString("name")))
                qAu = Double.parseDouble(e.getString("value"));
            if ("tp".equals(e.getString("name")))
                tpJd = Double.parseDouble(e.getString("value"));
        }
        // As the capture script: tp is JD TDB, less TT-UTC 69.184 s. (Comets.catalog does not subtract it: one minute of difference in the grid.)
        long tp = Math.round((tpJd - 2440587.5) * 864e5) - 69_184;
        long start = t("2026-04-14T12:28:29"), end = t("2026-05-11T22:36:29"); // ReversingComet.jhv's master window, as captured
        long now = t("2026-11-20T00:00:00");

        Map<String, String> answers = new HashMap<>(); // sidecar URL -> its captured result
        String[] states = {"horizons-comet-c_2025_r3-states-0", "horizons-comet-c_2025_r3-states-1", "horizons-comet-c_2025_r3-states-2",
                "horizons-soho-c_2025_r3-states", "horizons-stereo-a-c_2025_r3-states"};
        for (String name : states) {
            String url = fixture(name + ".url"), text = fixture(name + ".txt");
            if (url == null || text == null)
                return;
            answers.put(url.strip(), text);
        }
        List<String> asked = new ArrayList<>();
        CometEphemeris.Source captured = u -> {
            asked.add(u);
            String a = answers.get(u);
            if (a == null)
                throw new IOException("no captured answer for " + u);
            return a;
        };
        CometEphemeris comet = null;
        try {
            comet = CometEphemeris.fetch(new Comets.Comet("C/2025 R3", "PANSTARRS", qAu, tp), start, end, true, now, captured);
        } catch (IOException e) {
            expect(false, "fetch for C/2025 R3 is answered from the captured files (" + e.getMessage() + ")");
        }
        expect(asked.equals(List.of(fixture(states[0] + ".url").strip(), fixture(states[1] + ".url").strip(), fixture(states[2] + ".url").strip())),
                "fetch for C/2025 R3 asks exactly the three comet sidecar URLs (DES=C/2025 R3; CAP; NOFRAG;, the script's piece bounds)");
        expect(comet != null && comet.first() <= start - DAY && comet.last() >= end + DAY && comet.sampleTimes().length == 32,
                "and the captured answers make one 32-sample path covering the padded window");
        asked.clear();
        try {
            CometEphemeris.fetchBody("-21", "SOHO", start, end, true, now, captured);
            CometEphemeris.fetchBody("-234", "STEREO-A", start, end, true, now, captured);
        } catch (IOException e) {
            expect(false, "fetchBody is answered from the captured files (" + e.getMessage() + ")");
        }
        expect(asked.equals(List.of(fixture(states[3] + ".url").strip(), fixture(states[4] + ".url").strip())),
                "fetchBody asks exactly the SOHO (-21) and STEREO-A (-234) sidecar URLs");

        sidecar("horizons-earth-2025-06-21-ecliptic.url", JplHorizons.statesUrl("399", t("2025-06-20T00:00:00"), t("2025-06-22T00:00:00"), 360));
        sidecar("horizons-probe-2P.url", JplHorizons.statesUrl("DES=2P; CAP; NOFRAG;", t("2023-10-01T00:00:00"), t("2023-10-02T00:00:00"), 360));
        sidecar("horizons-probe-P_2P.url", JplHorizons.statesUrl("DES=P/2P; CAP; NOFRAG;", t("2023-10-01T00:00:00"), t("2023-10-02T00:00:00"), 360));

        // The observer tables, as Comets.ephemeris asks. URLEncoder writes a space as '+', the script's quote() as %20;
        // a literal '+' would be %2B in both, so swapping the one for the other is the whole difference.
        long step = Math.clamp((end - start) / 60_000 / 2000, 1, 30);
        try {
            String c = Comets.ephemerisUri("'DES=C/2025 R3; CAP;'", "'23,27'", start, end, step).toString();
            String sun = Comets.ephemerisUri("'10'", "'17,20'", start, end, step).toString();
            sidecar("horizons-comet-c_2025_r3-observer-soho.url", c.replace("+", "%20"), " once its '+' spaces are written %20");
            sidecar("horizons-sun-c_2025_r3-observer-soho.url", sun.replace("+", "%20"), " once its '+' spaces are written %20");
        } catch (java.net.URISyntaxException e) {
            expect(false, "Comets.ephemerisUri builds a URI (" + e + ")");
        }
    }

    private static void sidecar(String name, String built) {
        sidecar(name, built, ", byte for byte");
    }

    private static void sidecar(String name, String built, String how) {
        String want = fixture(name);
        if (want != null)
            expect(built.equals(want.strip()), "the request Studio builds equals " + name + how + (built.equals(want.strip()) ? "" : " (built " + built + ")"));
    }

    // Row 11
    private static void checkFixtureSourceTag() {
        String text = fixture(COMET_PIECES[0]);
        if (text == null)
            return;
        expect("{source: JPL#33}".equals(JplHorizons.sourceTag(text)),
                "sourceTag of the comet fixture: {source: JPL#33}, its Target body name line's tag (got " + JplHorizons.sourceTag(text) + ")");
    }

    // Review finding 12: Comets.catalog builds prefix + '/' + pdes; does Horizons resolve that form as well as the bare one?
    private static void checkProbes() throws IOException {
        String bare = fixture("horizons-probe-2P.txt"), slash = fixture("horizons-probe-P_2P.txt");
        if (bare == null || slash == null)
            return;
        boolean both = bare.contains("Target body name: 2P/Encke") && slash.contains("Target body name: 2P/Encke");
        JplHorizons.States a = JplHorizons.parseStates(bare), b = JplHorizons.parseStates(slash);
        expect(both && Arrays.equals(a.km(), b.km()) && a.milli().length == 5, "DES=2P and DES=P/2P both resolve to 2P/Encke, with the same five states");
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
