package org.helioviewer.jhv.astronomy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.JplHorizons;
import org.helioviewer.jhv.layers.ModelPlacement;
import org.helioviewer.jhv.math.Vec3;
import org.helioviewer.jhv.time.JHVTime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import spice.basic.SpiceErrorException;

/**
 * A comet's heliocentric path in 3D (or a spacecraft's, for SOHO's position), from JPL Horizons
 * state vectors: positions and velocities in the J2000 ecliptic frame, Sun-centred, in km.
 *
 * <p>Between samples the path is a cubic Hermite curve in that inertial frame, which uses the
 * velocities and so needs far fewer samples than straight lines for the same accuracy near
 * perihelion. The rotation into the rotating Carrington frame happens at display time, once for a
 * whole curve: {@link #world(long, JHVTime)} places the comet as it was at {@code milli} in the frame
 * of {@code frame}, the chain {@code ExternalEphemeris.at} uses (SPICE ECLIPJ2000 to
 * SOLO_IAU_SUN_2009, then {@link ModelPlacement}).
 *
 * <p>Sampling (comet3d plan, revision 2, section 1.4): at most three uniform requests, split at
 * perihelion minus and plus two days, each at the step its closest point to the Sun needs along a
 * parabola through the catalogue's perihelion. extra/tools/capture_horizons_fixtures.py segments()
 * builds the same pieces; the two must agree, so a change here changes the script too.
 *
 * <p>Answers are kept under {@code Catalogs/Comets} and never deleted by code.
 */
public final class CometEphemeris {

    /** Where something was at {@code milli}, in the world frame at {@code frame}; null when unknown. */
    @FunctionalInterface
    public interface WorldPath {
        @Nullable
        Vec3 world(long milli, JHVTime frame);
    }

    /** One uniform Horizons request. A comet's piece is on whole minutes and ends a whole number of steps after its start. */
    public record Segment(long start, long end, int stepMinutes) {}

    /** Where Horizons answers come from: the network, or a check's stand-in. */
    @FunctionalInterface
    interface Source {
        String result(String url) throws IOException;
    }

    private static final long MINUTE = 60_000L;
    private static final long DAY = 86_400_000L;

    static final int POLICY = 2;
    static final double STEP_K = 7.5; // minutes per Rsun^1.25
    static final int STEP_MIN = 2, STEP_MAX = 1440;
    static final long DENSE_HALF = 2 * DAY; // dense request: perihelion +- 2 days
    static final long PAD = DAY; // light-time and frame-time margin, each side
    static final long EXPIRE = 7 * DAY; // while perihelion is within a year: orbits are still being refined
    static final int BODY_STEP = 60; // minutes, for a spacecraft observer
    static final double GUARD = 0.9; // a measured minimum below this fraction of the grid's q resamples

    private static final long YEAR = 365 * DAY;
    // The parabola the step policy assumes, with the constants the capture script uses, so both build the same grid.
    private static final double GM_SUN = 1.3271244e20; // m^3 s^-2
    private static final double RSUN_M = 6.957e8;

    static final String COARSE = "coarse sampling: the catalogue gives no perihelion";
    static final String RESAMPLED = "resampled: Horizons puts it closer to the Sun than the catalogue's perihelion";

    private static final DateTimeFormatter DAY_NAME = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter STAMP_NAME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private final String label;
    private final long[] milli;
    private final double[] km;
    private final double[] kms;
    private final String sourceTag;
    private final long fetchedAt;
    private final double gridRsun; // the perihelion distance the request grid was built for; NaN for a body
    private final String note;
    private final double minDistanceRsun;

    private CometEphemeris(String label, JplHorizons.States s, String sourceTag, long fetchedAt, double gridRsun, String note) {
        this.label = label;
        milli = s.milli();
        km = s.km();
        kms = s.kms();
        this.sourceTag = sourceTag;
        this.fetchedAt = fetchedAt;
        this.gridRsun = gridRsun;
        this.note = note;
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < milli.length; i++)
            min = Math.min(min, Math.sqrt(km[3 * i] * km[3 * i] + km[3 * i + 1] * km[3 * i + 1] + km[3 * i + 2] * km[3 * i + 2]));
        minDistanceRsun = min * Sun.RadiusKMeterInv;
    }

    public static CometEphemeris of(String label, JplHorizons.States s, String sourceTag, long fetchedAt) {
        return new CometEphemeris(label, s, sourceTag, fetchedAt, Double.NaN, "");
    }

    private CometEphemeris withNote(String n) {
        return new CometEphemeris(label, new JplHorizons.States(milli, km, kms), sourceTag, fetchedAt, gridRsun, n);
    }

    // Sampling

    /** Step for a heliocentric distance in solar radii: clamp(floor(7.5 r^1.25), 2, 1440) minutes. */
    static int stepMinutes(double rRsun) {
        return (int) Math.clamp(Math.floor(STEP_K * Math.pow(rRsun, 1.25)), STEP_MIN, STEP_MAX);
    }

    /**
     * The requests for {@code [start, end]}: a dense piece around perihelion anchored at its start, the
     * piece before ending where it starts and the piece after starting where it ends, so neighbours
     * share a sample. With no perihelion ({@code perihelionMilli <= 0}: {@code Comets} stores a missing
     * one as 0) or no q, one request at the cap.
     */
    public static List<Segment> segments(double qRsun, long perihelionMilli, long start, long end) {
        long lo = floorMinute(start), hi = ceilMinute(end);
        List<Segment> out = new ArrayList<>(3);
        if (perihelionMilli <= 0 || !Double.isFinite(qRsun)) {
            int step = (int) Math.max(1, Math.ceilDiv(minutes(lo, hi), JplHorizons.MAX_STEPS));
            out.add(new Segment(lo, lo + step * MINUTE * Math.ceilDiv(minutes(lo, hi), step), step));
            return out;
        }
        long tp = perihelionMilli;
        long a = floorMinute(tp - DENSE_HALF), b = ceilMinute(tp + DENSE_HALF);
        long dLo = Math.max(lo, a), dHi = Math.min(hi, b);
        if (dHi > dLo) {
            double r = dLo <= tp && tp <= dHi ? qRsun : parabolaRsun(qRsun, (dHi < tp ? dHi - tp : dLo - tp) / 1000.);
            int step = capped(stepMinutes(r), dLo, dHi);
            out.add(new Segment(dLo, dLo + step * MINUTE * Math.ceilDiv(minutes(dLo, dHi), step), step));
        }
        if (lo < a) {
            long e = out.isEmpty() ? Math.min(hi, a) : out.getFirst().start();
            int step = capped(stepMinutes(parabolaRsun(qRsun, (e - tp) / 1000.)), lo, e);
            out.addFirst(new Segment(e - step * MINUTE * Math.ceilDiv(minutes(lo, e), step), e, step));
        }
        if (hi > b) {
            long s = out.isEmpty() || out.getLast().end() <= a ? Math.max(lo, b) : out.getLast().end();
            int step = capped(stepMinutes(parabolaRsun(qRsun, (s - tp) / 1000.)), s, hi);
            // The dense piece's last step can reach past the window's end; then nothing is left to ask for.
            if (hi > s)
                out.add(new Segment(s, s + step * MINUTE * Math.ceilDiv(minutes(s, hi), step), step));
        }
        return out;
    }

    /**
     * A spacecraft's request: the window as given, seconds included, at one hour, coarser only to stay
     * under the cap. The last sample can fall up to a step before {@code end}, inside the pad.
     */
    static Segment bodySegment(long start, long end) {
        int step = (int) Math.max(BODY_STEP, Math.ceilDiv(Math.max(end - start, MINUTE), JplHorizons.MAX_STEPS * MINUTE));
        return new Segment(start, end, step);
    }

    private static int capped(int step, long a, long b) {
        long m = minutes(a, b);
        return m <= (long) JplHorizons.MAX_STEPS * step ? step : (int) Math.ceilDiv(m, JplHorizons.MAX_STEPS);
    }

    private static long minutes(long a, long b) {
        return (b - a) / MINUTE;
    }

    private static long floorMinute(long t) {
        return Math.floorDiv(t, MINUTE) * MINUTE;
    }

    private static long ceilMinute(long t) {
        return Math.ceilDiv(t, MINUTE) * MINUTE;
    }

    /** Heliocentric distance on a parabola of perihelion {@code qRsun}, {@code dtSeconds} from perihelion (Barker's equation). */
    static double parabolaRsun(double qRsun, double dtSeconds) {
        double qm = qRsun * RSUN_M;
        double w = dtSeconds / Math.sqrt(2 * qm * qm * qm / GM_SUN); // D + D^3/3 = w
        double y = Math.pow(1.5 * w + Math.sqrt(2.25 * w * w + 1), 1. / 3);
        double d = y - 1 / y;
        return qRsun * (1 + d * d);
    }

    // Fetching and the cache

    /** What {@link #fetch} and {@link #fetchBody} ask for: {@code [start, end]} widened by {@link #PAD} each side. */
    public static long[] window(long start, long end) {
        return new long[]{start - PAD, end + PAD};
    }

    private record Request(String command, String designation, String label, String slug, long perihelion, long start, long end) {}

    /** The comet over {@code [start, end]} padded by {@link #PAD} each side; cached. Off the event thread. */
    public static CometEphemeris fetch(Comets.Comet comet, long start, long end, boolean refresh) throws IOException {
        return fetch(comet, start, end, refresh, System.currentTimeMillis(), JplHorizons::fetchText);
    }

    static CometEphemeris fetch(Comets.Comet comet, long start, long end, boolean refresh, long now, Source horizons) throws IOException {
        double q = comet.perihelionRsun();
        long tp = comet.perihelion();
        Request r = new Request("DES=" + comet.designation() + "; CAP; NOFRAG;", comet.designation(), comet.label(),
                slug(comet.designation()), tp, window(start, end)[0], window(start, end)[1]);
        CometEphemeris e = load(r, q, segments(q, tp, r.start(), r.end()), refresh, now, horizons);
        if (tp <= 0 || !Double.isFinite(q))
            return e.withNote(COARSE);
        // A posteriori: an orbit tighter than the parabola the grid assumed gets one resample at the measured minimum.
        if (e.minDistanceRsun < GUARD * e.gridRsun) {
            double measured = e.minDistanceRsun;
            e = load(r, measured, segments(measured, tp, r.start(), r.end()), true, now, horizons);
        }
        return e.gridRsun < q ? e.withNote(RESAMPLED) : e;
    }

    /** A spacecraft (SOHO is {@code "-21"}) over {@code [start, end]} padded by {@link #PAD}, hourly; cached. Off the event thread. */
    public static CometEphemeris fetchBody(String command, String label, long start, long end, boolean refresh) throws IOException {
        return fetchBody(command, label, start, end, refresh, System.currentTimeMillis(), JplHorizons::fetchText);
    }

    static CometEphemeris fetchBody(String command, String label, long start, long end, boolean refresh, long now, Source horizons) throws IOException {
        Request r = new Request(command, command, label, "body" + slug(command), 0, window(start, end)[0], window(start, end)[1]);
        return load(r, Double.NaN, List.of(bodySegment(r.start(), r.end())), refresh, now, horizons);
    }

    private static CometEphemeris load(Request r, double gridRsun, List<Segment> plan, boolean refresh, long now, Source horizons) throws IOException {
        Path dir = cacheDir();
        if (!refresh) {
            CometEphemeris hit = lookup(dir, r.slug(), r.start(), r.end(), r.perihelion(), now);
            if (hit != null)
                return hit;
        }
        List<String> urls = new ArrayList<>(plan.size()), results = new ArrayList<>(plan.size());
        for (Segment s : plan) {
            String url = JplHorizons.statesUrl(r.command(), s.start(), s.end(), s.stepMinutes());
            urls.add(url);
            results.add(horizons.result(url));
        }
        CometEphemeris e = parse(r.label(), results, now, gridRsun);
        try {
            store(dir, r, e, urls, results);
        } catch (IOException | JSONException x) {
            Log.warn("Comet ephemeris: could not write the cache in " + dir, x);
        }
        return e;
    }

    static Path cacheDir() {
        return Path.of(Directories.CATALOGS.getPath(), "Comets");
    }

    static String slug(String designation) {
        return designation.replaceAll("[^A-Za-z0-9-]", "_");
    }

    /** {@code start} and {@code end}: the padded window the file answers. */
    static String fileName(String slug, long start, long end, long stamp) {
        return slug + '_' + DAY_NAME.format(Instant.ofEpochMilli(start)) + '_' + DAY_NAME.format(Instant.ofEpochMilli(end)) + '_'
                + STAMP_NAME.format(Instant.ofEpochMilli(stamp)) + "_v" + POLICY + ".json";
    }

    private static void store(Path dir, Request r, CometEphemeris e, List<String> urls, List<String> results) throws IOException {
        JSONArray requests = new JSONArray();
        for (int i = 0; i < urls.size(); i++)
            requests.put(new JSONObject().put("url", urls.get(i)).put("result", results.get(i)));
        JSONObject jo = new JSONObject().put("designation", r.designation()).put("label", r.label()).put("policy", POLICY)
                .put("fetched", e.fetchedAt).put("start", r.start()).put("end", r.end()).put("source", e.sourceTag).put("requests", requests);
        if (Double.isFinite(e.gridRsun))
            jo.put("gridRsun", e.gridRsun);

        Files.createDirectories(dir);
        long stamp = e.fetchedAt;
        Path file = dir.resolve(fileName(r.slug(), r.start(), r.end(), stamp));
        while (Files.exists(file)) { // two fetches in one second: never overwrite, take the next free name
            stamp += 1000;
            file = dir.resolve(fileName(r.slug(), r.start(), r.end(), stamp));
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(tmp, jo.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException x) {
            Files.move(tmp, file);
        }
    }

    /**
     * The newest cached answer for {@code slug} whose window covers {@code [start, end]}, or null. A file
     * older than {@link #EXPIRE} is passed over while the perihelion is within a year of {@code now}. A
     * file that cannot be read is passed over and left where it is.
     */
    @Nullable
    static CometEphemeris lookup(Path dir, String slug, long start, long end, long perihelion, long now) {
        String startDay = DAY_NAME.format(Instant.ofEpochMilli(start)), endDay = DAY_NAME.format(Instant.ofEpochMilli(end));
        String suffix = "_v" + POLICY + ".json";
        List<String[]> named = new ArrayList<>(); // file name, stamp
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (!n.startsWith(slug + '_') || !n.endsWith(suffix))
                    continue;
                String[] p = n.substring(slug.length() + 1, n.length() - suffix.length()).split("_");
                if (p.length == 3 && p[0].compareTo(startDay) <= 0 && p[1].compareTo(endDay) >= 0)
                    named.add(new String[]{n, p[2]});
            }
        } catch (IOException e) {
            return null; // no cache folder yet
        }
        named.sort(Comparator.comparing((String[] p) -> p[1]).reversed());
        boolean refining = perihelion > 0 && Math.abs(perihelion - now) < YEAR;
        for (String[] p : named) {
            Path f = dir.resolve(p[0]);
            try {
                JSONObject jo = new JSONObject(Files.readString(f, StandardCharsets.UTF_8));
                long fetched = jo.getLong("fetched");
                if (refining && now - fetched > EXPIRE || jo.getLong("start") > start || jo.getLong("end") < end)
                    continue;
                JSONArray requests = jo.getJSONArray("requests");
                List<String> results = new ArrayList<>(requests.length());
                for (int i = 0; i < requests.length(); i++)
                    results.add(requests.getJSONObject(i).getString("result"));
                return parse(jo.optString("label", slug), results, fetched, jo.optDouble("gridRsun", Double.NaN));
            } catch (IOException | JSONException e) {
                Log.warn("Comet ephemeris: skipping unreadable cache file " + f, e);
            }
        }
        return null;
    }

    static CometEphemeris parse(String label, List<String> results, long fetchedAt, double gridRsun) throws IOException {
        if (results.isEmpty())
            throw new IOException("No Horizons answer to read");
        List<JplHorizons.States> parts = new ArrayList<>(results.size());
        for (String r : results)
            parts.add(JplHorizons.parseStates(r));
        return new CometEphemeris(label, merge(parts), JplHorizons.sourceTag(results.getFirst()), fetchedAt, gridRsun, "");
    }

    /** The pieces in time order, each sample once: neighbouring requests share their boundary sample. */
    static JplHorizons.States merge(List<JplHorizons.States> parts) {
        int total = 0;
        for (JplHorizons.States p : parts)
            total += p.milli().length;
        long[] t = new long[total];
        double[] p3 = new double[3 * total], v3 = new double[3 * total];
        int n = 0;
        for (JplHorizons.States p : parts)
            for (int i = 0; i < p.milli().length; i++) {
                if (n > 0 && p.milli()[i] <= t[n - 1])
                    continue;
                t[n] = p.milli()[i];
                System.arraycopy(p.km(), 3 * i, p3, 3 * n, 3);
                System.arraycopy(p.kms(), 3 * i, v3, 3 * n, 3);
                n++;
            }
        return new JplHorizons.States(Arrays.copyOf(t, n), Arrays.copyOf(p3, 3 * n), Arrays.copyOf(v3, 3 * n));
    }

    // Positions

    /** The J2000 ecliptic position in km at {@code t}, cubic Hermite between samples; null outside [first, last]. */
    @Nullable
    public double[] eclipticKm(long t) {
        int last = milli.length - 1;
        if (last < 1 || t < milli[0] || t > milli[last])
            return null;
        int lo = 0, hi = last; // lo: the last sample at or before t, kept below last so lo + 1 exists
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (milli[mid] <= t)
                lo = mid;
            else
                hi = mid;
        }
        double h = (milli[lo + 1] - milli[lo]) / 1000.; // seconds, since velocities are km/s
        double s = (t - milli[lo]) / 1000. / h, s2 = s * s, s3 = s2 * s;
        double h00 = 2 * s3 - 3 * s2 + 1, h10 = (s3 - 2 * s2 + s) * h, h01 = -2 * s3 + 3 * s2, h11 = (s3 - s2) * h;
        double[] p = new double[3];
        for (int k = 0; k < 3; k++) {
            int i0 = 3 * lo + k, i1 = i0 + 3;
            p[k] = h00 * km[i0] + h10 * kms[i0] + h01 * km[i1] + h11 * kms[i1];
        }
        return p;
    }

    /** Where it was at {@code milli}, in the world frame at {@code frame}; null outside the samples or if SPICE fails. */
    @Nullable
    public Vec3 world(long t, JHVTime frame) {
        double[] p = eclipticKm(t);
        if (p == null)
            return null;
        try {
            return toWorld(eclipticToCarrington(frame.milli), p, frame);
        } catch (SpiceErrorException e) {
            Log.warn("Comet ephemeris: no frame rotation at " + frame, e);
            return null;
        }
    }

    /** x, y, z per time, all rotated with one matrix at {@code frame}; NaN outside the samples; null if SPICE fails. */
    @Nullable
    public double[] worldAll(long[] t, JHVTime frame) {
        double[][] m;
        try {
            m = eclipticToCarrington(frame.milli);
        } catch (SpiceErrorException e) {
            Log.warn("Comet ephemeris: no frame rotation at " + frame, e);
            return null;
        }
        double[] out = new double[3 * t.length];
        for (int i = 0; i < t.length; i++) {
            double[] p = eclipticKm(t[i]);
            Vec3 w = p == null ? null : toWorld(m, p, frame);
            out[3 * i] = w == null ? Double.NaN : w.x;
            out[3 * i + 1] = w == null ? Double.NaN : w.y;
            out[3 * i + 2] = w == null ? Double.NaN : w.z;
        }
        return out;
    }

    /** ECLIPJ2000 to SOLO_IAU_SUN_2009 at {@code frameMilli}, columns from three unit vectors (Spice keeps its own matrix helper private). */
    public static double[][] eclipticToCarrington(long frameMilli) throws SpiceErrorException {
        double[][] m = new double[3][3];
        for (int c = 0; c < 3; c++) {
            double[] unit = new double[3];
            unit[c] = 1;
            double[] col = Spice.rotate("ECLIPJ2000", "SOLO_IAU_SUN_2009", frameMilli, unit);
            for (int r = 0; r < 3; r++)
                m[r][c] = col[r];
        }
        return m;
    }

    private static Vec3 toWorld(double[][] m, double[] p, JHVTime frame) {
        double x = (m[0][0] * p[0] + m[0][1] * p[1] + m[0][2] * p[2]) * Sun.RadiusKMeterInv;
        double y = (m[1][0] * p[0] + m[1][1] * p[1] + m[1][2] * p[2]) * Sun.RadiusKMeterInv;
        double z = (m[2][0] * p[0] + m[2][1] * p[1] + m[2][2] * p[2]) * Sun.RadiusKMeterInv;
        return ModelPlacement.toWorld(ModelPlacement.carringtonPosition(frame, x, y, z));
    }

    public String label() {
        return label;
    }

    public long first() {
        return milli[0];
    }

    public long last() {
        return milli[milli.length - 1];
    }

    public long[] sampleTimes() {
        return milli.clone();
    }

    public double minDistanceRsun() {
        return minDistanceRsun;
    }

    /** Horizons' {@code {source: ...}} tag: which orbit solution answered. */
    public String sourceTag() {
        return sourceTag;
    }

    public long fetchedAt() {
        return fetchedAt;
    }

    /** "" or a line for the status: coarse sampling, or resampled after the guard. */
    public String note() {
        return note;
    }

}
