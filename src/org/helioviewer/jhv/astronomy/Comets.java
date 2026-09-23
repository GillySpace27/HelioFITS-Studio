package org.helioviewer.jhv.astronomy;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.helioviewer.jhv.io.JSONUtils;
import org.helioviewer.jhv.io.NetClient;

import com.google.common.io.CharStreams;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Near-Sun comets, from JPL: the catalog from the Small-Body Database query API, the track from
 * Horizons. Two services rather than one because they answer different questions — "which comets
 * came this close while my movie was running" and "where was that one on the sky, minute by
 * minute" — and only the second is expensive, so it is asked once, for the comet actually picked.
 *
 * <p>What a track carries is deliberately the same pair the CME tracker already animates: a
 * plane-of-sky radius in solar radii and a position angle. A CME gets them from a constant-speed
 * extrapolation; a comet gets them from an ephemeris, interpolated between samples. Everything
 * downstream — the Box-Cox warp solve, the radial crop, the front markers — is then identical.
 *
 * <p>Two limits worth knowing before trusting a result:
 *
 * <ul>
 * <li>The catalog is JPL's comet table, not the Sungrazer project's discovery list. JPL carries the
 * SOHO Kreutz comets densely for 1996-2008 (around a hundred a year) and only a handful a year
 * after that, so a 2015 movie will look emptier than the LASCO frames actually are. Nothing can be
 * done about that here: an object with no published orbit has no ephemeris either, so it could be
 * listed but never tracked.
 * <li>The ephemeris is computed for SOHO. At L1 that is exact for LASCO and within about a percent
 * of the parallax for anything else on the Sun-Earth line (AIA, PUNCH); it would be badly wrong for
 * STEREO or Solar Orbiter. // ponytail: one observer, upgrade path is a Horizons center per
 * SpaceObject when a non-L1 coronagraph needs it.
 * </ul>
 */
public final class Comets {

    private static final String SBDB = "https://ssd-api.jpl.nasa.gov/sbdb_query.api";
    private static final String HORIZONS = "https://ssd.jpl.nasa.gov/api/horizons.api";

    // The SBDB endpoint answers 502 for something like a third of otherwise identical requests, and
    // has done so for every query shape tried; Horizons next door does not. So the catalog search
    // retries and the ephemeris does not. // ponytail: fixed attempts, no backoff curve; if JPL gets
    // worse, the Refresh button is the other half of the answer.
    private static final int SEARCH_ATTEMPTS = 4;
    private static final long SEARCH_RETRY_MILLI = 400;

    private static final String OBSERVER = "'500@-21'"; // SOHO, in Horizons' own quoted-argument form

    // Which comets are worth asking about at all. Not a statement about what will be in the field:
    // perihelion distance cannot answer that, because a comet at 1 au projects onto Sun centre when
    // it passes through conjunction, and a comet with a tiny perihelion is nowhere near the Sun a
    // month later. These two only bound the number of ephemerides fetched; the field decides the
    // rest. // ponytail: a flat cap rather than a density model. In the sungrazer years the nearest
    // twelve in perihelion time are the ones in the movie; the status line says when the cap bit.
    // The window is six months rather than a few weeks because PUNCH exists. A C3 movie only ever
    // shows a comet within days of perihelion or of conjunction; the PUNCH mosaic reaches past 150
    // solar radii, where a comet three months from perihelion is an ordinary sight. Sorting by
    // perihelion time and capping keeps the cost the same in both cases: for a coronagraph movie
    // the nearest dozen are the ones near the movie anyway.
    private static final double MAX_PERIHELION_AU = 2;
    private static final long SEARCH_PAD = 180 * 86400_000L;
    private static final int MAX_CANDIDATES = 16;

    private static final int TRACK_SAMPLES = 2000;
    private static final long TRACK_STEP_CEILING = 30; // minutes
    private static final int SURVEY_SAMPLES = 120; // coarse: this pass only has to find the minimum

    // Stands in for "no plane-of-sky distance exists here": large enough to be outside any field a
    // coronagraph has, finite so that interpolating across it cannot produce a NaN.
    static final double OFF_THE_PLANE = 1e6;

    private static final double JD_UNIX_EPOCH = 2440587.5;
    private static final DateTimeFormatter HORIZONS_IN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter HORIZONS_OUT = DateTimeFormatter.ofPattern("yyyy-MMM-dd HH:mm", Locale.ENGLISH);

    /** One catalog row: enough to list it, and the designation Horizons needs to expand it into a track. */
    public record Comet(String designation, String name, double perihelionAu, long perihelion) {
        /** Perihelion distance in solar radii, which is how far in it got if the orbit lay in the plane of sky. */
        public double perihelionRsun() {
            return perihelionAu * Sun.MeanEarthDistance;
        }

        public String label() {
            return name == null || name.isEmpty() ? designation : designation + " (" + name + ')';
        }
    }

    /**
     * A sampled plane-of-sky track. Radius is linear between samples; position angle is too, but
     * across the shortest arc, or a comet sweeping past perihelion from 359° to 1° would spin the
     * marker the long way round.
     */
    public record Track(long[] milli, double[] rSun, double[] paDeg) {

        public double radius(double at) {
            if (rSun.length == 1) // a one-sample track holds; there is no pair to interpolate between
                return rSun[0];
            int i = bracket(at);
            return lerp(at, i, rSun[i], rSun[i + 1]);
        }

        public double positionAngle(double at) {
            if (paDeg.length == 1)
                return paDeg[0];
            int i = bracket(at);
            double a = paDeg[i], b = paDeg[i + 1];
            double delta = ((b - a) % 360 + 540) % 360 - 180; // shortest way round
            return ((lerp(at, i, a, a + delta)) % 360 + 360) % 360;
        }

        private int bracket(double at) {
            int last = milli.length - 1;
            if (last == 0)
                return 0;
            int lo = 0, hi = last;
            while (lo < hi - 1) { // the grid is uniform, but a binary search costs nothing and does not assume it
                int mid = (lo + hi) >>> 1;
                if (milli[mid] <= at)
                    lo = mid;
                else
                    hi = mid;
            }
            return lo;
        }

        private double lerp(double at, int i, double a, double b) {
            long t0 = milli[i], t1 = milli[i + 1];
            double f = t1 == t0 ? 0 : Math.clamp((at - t0) / (double) (t1 - t0), 0, 1); // clamped: before the
            return a + f * (b - a);                                                     // first sample and after
        }                                                                               // the last, the track holds

        /** Time of the closest approach to Sun centre on the sky, which is where a pass is worth starting. */
        public long closest() {
            int best = 0;
            for (int i = 1; i < rSun.length; i++)
                if (rSun[i] < rSun[best])
                    best = i;
            return milli[best];
        }

        /**
         * The first moment the comet is inside {@code fovRsun}, or {@link #closest()} when it never is —
         * so tracking starts as it enters the frame rather than at the climax.
         */
        public long entry(double fovRsun) {
            for (int i = 0; i < rSun.length; i++)
                if (rSun[i] <= fovRsun)
                    return milli[i];
            return closest();
        }

        public double minRadius() {
            double min = rSun[0];
            for (double r : rSun)
                min = Math.min(min, r);
            return min;
        }
    }

    /** A comet the ephemeris puts inside the loaded field, and how close to Sun centre it came. */
    public record Sighting(Comet comet, double minRadius, long closest) {}

    /**
     * The comets actually in the field during a movie.
     *
     * <p>The first version of this asked the catalog a proxy question -- whose perihelion falls near
     * these dates, and is it small -- and reported the answer as though it were the real one. It is
     * not even close. A comet crossing the field at conjunction weeks from perihelion was invisible
     * to it, and a sungrazer whose perihelion landed in the window was listed whether or not it was
     * anywhere near the movie. So the catalog now only nominates candidates, and an ephemeris
     * decides, which is the only thing that can.
     *
     * <p>Costs one Horizons request per candidate plus one for the Sun, so it is bounded by
     * {@link #MAX_CANDIDATES} and reports progress rather than going quiet for twenty seconds.
     */
    public static List<Sighting> search(long start, long end, double fieldRsun, Consumer<String> progress) throws Exception {
        List<Comet> candidates = catalog(start, end);
        boolean capped = candidates.size() > MAX_CANDIDATES;
        if (capped)
            candidates = candidates.subList(0, MAX_CANDIDATES);
        if (candidates.isEmpty())
            return List.of();

        long step = Math.max(1, (end - start) / 60_000 / SURVEY_SAMPLES);
        String[] sun = rows(ephemerisText("'10'", "'17,20'", start, end, step));

        List<Sighting> seen = new ArrayList<>();
        int checked = 0;
        for (Comet comet : candidates) {
            progress.accept("Checking " + comet.label() + " against the field (" + ++checked + " of " + candidates.size() + ")…");
            Track track;
            try {
                track = parse(rows(ephemerisText("'DES=" + comet.designation() + "; CAP;'", "'23,27'", start, end, step)),
                        sun, comet.designation());
            } catch (Exception e) {
                continue; // catalogued but not integrable by Horizons: it could never be tracked either
            }
            if (track.minRadius() <= fieldRsun)
                seen.add(new Sighting(comet, track.minRadius(), track.closest()));
        }
        seen.sort(Comparator.comparingLong(Sighting::closest));
        if (capped)
            progress.accept("Checked the " + MAX_CANDIDATES + " nearest perihelia only; shorten the movie to see the rest.");
        return seen;
    }

    /** Candidates from the catalog, nearest perihelion first, before any geometry is applied. */
    private static List<Comet> catalog(long start, long end) throws Exception {
        URI uri = new URI(query(SBDB,
                "fields", "prefix,pdes,name,q,tp",
                "sb-kind", "c",
                "sb-cdata", "{\"AND\":[\"q|LT|" + MAX_PERIHELION_AU +
                        "\",\"tp|GE|" + String.format(Locale.ROOT, "%.5f", julianDay(start - SEARCH_PAD)) +
                        "\",\"tp|LE|" + String.format(Locale.ROOT, "%.5f", julianDay(end + SEARCH_PAD)) + "\"]}"));

        JSONObject result = retry(uri);
        JSONArray data = result.optJSONArray("data");
        if (data == null)
            throw new Exception(result.optString("message", "Unexpected SBDB response"));

        List<Comet> comets = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONArray row = data.getJSONArray(i);
            String prefix = row.optString(0, "");
            String pdes = row.optString(1, "");
            if (pdes.isEmpty())
                continue;
            comets.add(new Comet(prefix.isEmpty() ? pdes : prefix + '/' + pdes, row.optString(2, ""),
                    row.optDouble(3, Double.NaN), milliOf(row.optDouble(4, Double.NaN))));
        }
        long middle = start + (end - start) / 2;
        comets.sort(Comparator.comparingLong(c -> Math.abs(c.perihelion - middle)));
        return comets;
    }

    /**
     * The comet's plane-of-sky track over an interval, as SOHO sees it.
     *
     * <p>Horizons reports the solar elongation (S-O-T) and the position angle of the extended
     * Sun-to-target radius vector (PsAng), both in the observer's sky and both referred to the
     * celestial north pole. A coronagraph frame is referred to the solar north pole instead, which
     * is why the Sun's own ephemeris is fetched on the same grid: its north-pole position angle
     * turns PsAng into the angle CACTus and the trackers use, and its range sets the apparent solar
     * radius that turns an elongation into solar radii.
     */
    public static Track ephemeris(Comet comet, long start, long end) throws Exception {
        if (start >= end)
            throw new Exception("End before start");
        // Minutes, and never coarser than the ceiling however long the movie is: a sungrazer crosses
        // the whole field in hours, so a step scaled only to a six-week movie would interpolate
        // straight across its perihelion. Horizons prints seconds in the date below a one-minute step.
        long step = Math.clamp((end - start) / 60_000 / TRACK_SAMPLES, 1, TRACK_STEP_CEILING);

        return parse(rows(ephemerisText("'DES=" + comet.designation + "; CAP;'", "'23,27'", start, end, step)),
                rows(ephemerisText("'10'", "'17,20'", start, end, step)), comet.designation());
    }

    /**
     * Turn the two CSV ephemeris blocks into a track. Separate from the fetch so it can be checked
     * against captured output rather than against the network.
     *
     * <p>The two requests share a time grid, so their rows pair up; a row either side of the pair
     * that cannot be read is dropped rather than guessed at.
     */
    static Track parse(String[] target, String[] sun, String what) throws Exception {
        int len = Math.min(target.length, sun.length);
        if (len == 0)
            throw new Exception("No ephemeris for " + what);

        long[] milli = new long[len];
        double[] rSun = new double[len];
        double[] paDeg = new double[len];
        int n = 0;
        for (int i = 0; i < len; i++) {
            String[] tf = target[i].split(",");
            String[] sf = sun[i].split(",");
            if (tf.length < 7 || sf.length < 6)
                continue;
            int flag = -1; // the /T, /L, /* or /? marker sits between S-O-T and PsAng, whatever the leading
            for (int c = 1; c < tf.length; c++) // presence columns did, so find it rather than counting commas
                if (tf[c].trim().startsWith("/")) {
                    flag = c;
                    break;
                }
            if (flag < 2 || flag + 1 >= tf.length)
                continue;

            double elongDeg = number(tf[flag - 1]);
            double psAngDeg = number(tf[flag + 1]);
            double npAngDeg = number(sf[3]);     // Sun's north-pole position angle, quantity 17
            double sunDistAu = number(sf[5]);    // observer-Sun range, quantity 20
            if (Double.isNaN(elongDeg) || Double.isNaN(psAngDeg) || Double.isNaN(npAngDeg) || Double.isNaN(sunDistAu))
                continue;

            milli[n] = parseHorizonsTime(tf[0]);
            rSun[n] = projectedRadius(elongDeg, sunDistAu);
            paDeg[n] = ((psAngDeg - npAngDeg) % 360 + 360) % 360;
            n++;
        }
        if (n == 0)
            throw new Exception("Unreadable ephemeris for " + what);
        return new Track(Arrays.copyOf(milli, n), Arrays.copyOf(rSun, n), Arrays.copyOf(paDeg, n));
    }

    /**
     * Apparent distance from Sun centre, in solar radii: an elongation on the sky projected onto the
     * plane through the Sun and divided by the Sun's own apparent radius from the same place, which
     * is exactly the unit a coronagraph frame is calibrated in.
     *
     * <p>That projection is a tangent, so it runs away at a right angle and comes back NEGATIVE
     * past it. While only a picked comet was ever measured this did not matter, because nobody
     * picks a comet on the far side of the sky. Asking it about every candidate does matter: a
     * comet at 108 degrees elongation scored -1322 solar radii, and a negative number is inside
     * every field there is, so it was listed as being in the picture. Past a right angle there is
     * no plane-of-sky distance to report, and saying so is the honest answer.
     */
    static double projectedRadius(double elongDeg, double sunDistAu) {
        if (elongDeg >= 90)
            return OFF_THE_PLANE;
        double distRsun = sunDistAu * Sun.MeanEarthDistance;
        return Math.tan(Math.toRadians(elongDeg)) / Math.tan(Math.asin(1 / distRsun));
    }

    /** The lines between Horizons' $$SOE and $$EOE markers, which is the ephemeris and nothing else. */
    static String[] rows(String text) throws Exception {
        int soe = text.indexOf("$$SOE");
        int eoe = text.indexOf("$$EOE");
        if (soe < 0 || eoe < soe)
            throw new Exception(errorOf(text));
        return text.substring(soe + 5, eoe).strip().split("\\R");
    }

    private static String errorOf(String text) {
        for (String line : text.split("\\R"))
            if (line.contains("Error") || line.contains("error") || line.contains("No matches"))
                return line.strip();
        return "Horizons returned no ephemeris";
    }

    private static String ephemerisText(String command, String quantities, long start, long end, long stepMinutes) throws Exception {
        URI uri = new URI(query(HORIZONS,
                "format", "text",
                "COMMAND", command,
                "OBJ_DATA", "NO",
                "MAKE_EPHEM", "YES",
                "EPHEM_TYPE", "OBSERVER",
                "CENTER", OBSERVER,
                "START_TIME", '\'' + format(start) + '\'',
                "STOP_TIME", '\'' + format(end) + '\'',
                "STEP_SIZE", "'" + stepMinutes + " m'",
                "QUANTITIES", quantities,
                "CSV_FORMAT", "'YES'"));
        try (NetClient nc = NetClient.of(uri, true); Reader reader = nc.getReader()) {
            return CharStreams.toString(reader);
        }
    }

    /**
     * Not UriTemplate: that one opens with "?&", which every service tried so far tolerates and
     * SBDB answers 400 to.
     */
    private static String query(String base, String... keyValues) {
        StringBuilder builder = new StringBuilder(base);
        for (int i = 0; i + 1 < keyValues.length; i += 2)
            builder.append(i == 0 ? '?' : '&').append(keyValues[i]).append('=')
                    .append(URLEncoder.encode(keyValues[i + 1], StandardCharsets.UTF_8));
        return builder.toString();
    }

    private static JSONObject retry(URI uri) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < SEARCH_ATTEMPTS; attempt++) {
            if (attempt > 0)
                Thread.sleep(SEARCH_RETRY_MILLI);
            try {
                return JSONUtils.get(uri);
            } catch (IOException e) {
                last = e;
            }
        }
        throw new Exception("JPL did not answer after " + SEARCH_ATTEMPTS + " tries", last);
    }

    private static double number(String field) {
        try {
            return Double.parseDouble(field.strip());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // Horizons prints "2025-Jan-13 06:00", which none of the application's own formats parse, so
    // this stays local to the one place that sees it.
    private static long parseHorizonsTime(String field) {
        return LocalDateTime.parse(field.strip(), HORIZONS_OUT).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static String format(long milli) {
        return HORIZONS_IN.format(LocalDateTime.ofEpochSecond(milli / 1000, 0, ZoneOffset.UTC));
    }

    static double julianDay(long milli) {
        return milli / 86400000. + JD_UNIX_EPOCH;
    }

    private static long milliOf(double julianDay) {
        return Double.isNaN(julianDay) ? 0 : Math.round((julianDay - JD_UNIX_EPOCH) * 86400000.);
    }

    private Comets() {
    }

}
