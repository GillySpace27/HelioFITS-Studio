package org.helioviewer.jhv.io;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.time.TimeUtils;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;

/**
 * LASCO monthly minimum images, which are what makes a C3 frame readable.
 *
 * <p>A raw level-0.5 coronagraph frame is dominated by the static K-corona, F-corona and stray
 * light, and level-1 calibration does not remove any of it: the browse products everyone
 * recognizes are background-subtracted, which is why an 8-bit JPEG can look better than the 16-bit
 * FITS it came from. NRL builds the background by taking, for each pixel, the minimum non-zero
 * value over about four weeks of daily median images, and publishes them at
 * {@code content/retrieve/monthly} named {@code tm_fwpw_yymmdd.fts}: telescope, monthly, filter
 * and polarizer abbreviations, and the MID-POINT date of the range used.
 *
 * <p>The two images bracketing a frame are interpolated in time, which is what NRL's own
 * {@code getbkgimg.pro} does. Age matters: the same C3 frame minus a 2025 background shows
 * streamers cleanly, and minus the 1996 background that SolarSoft ships it keeps a bright residual
 * halo. The archive is current, with entries through 2026.
 */
public final class LascoBackground {

    private static final String BASE_URL = "https://lasco-www.nrl.navy.mil/content/retrieve/monthly/";
    private static final Pattern ENTRY = Pattern.compile("([123])m_([a-z]{2})([a-z]{2})_(\\d{6})\\.fts");
    // Filenames are two-digit years over a mission that began in 1995, so the century has to be
    // decided rather than assumed; the archive holds 96 through the present.
    private static final int CENTURY_PIVOT = 96;

    /**
     * Whether a new layer starts with its background subtracted. Each layer then keeps its own
     * choice (the Background section of its options); this property only sets where it starts.
     */
    public static boolean subtractByDefault() {
        return !"false".equals(Settings.getProperty("display.lascoBackground"));
    }

    /** Where a layer's background comes from. The NRL modes need the network; the local ones read the user's files. */
    public enum Mode {
        INTERPOLATED("Auto: NRL, interpolated"), NEAREST("Auto: NRL, nearest"), LOCAL_FILE("Local file"), LOCAL_PAIR("Local pair");

        private final String label;

        Mode(String _label) {
            label = _label;
        }

        public boolean local() {
            return this == LOCAL_FILE || this == LOCAL_PAIR;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * A layer's background choice: the mode, and for the local modes the file or files ("" when
     * unset). A record, so it can sit in the decode parameters and a frame decoded under one choice
     * is never reused for another.
     */
    public record Choice(Mode mode, String fileA, String fileB) {
        public static final Choice DEFAULT = new Choice(Mode.INTERPOLATED, "", "");
    }

    /**
     * A background was asked for but cannot be used, for a reason that retrying will not change (a
     * local file without EXPTIME, of the wrong size, missing). The frame is shown without it, and
     * the reason is what the Background section says.
     */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    /**
     * A resolved background: DN per second, rows in FITS order, and anything the user should know
     * about it (a mismatch, a frame outside a local pair's dates), or null.
     */
    public record Result(float[] perSecond, @Nullable String note) {}

    /**
     * Which background one frame would have subtracted: the product it belongs to, its moment and
     * its size. Carried on the decoded frame whether or not the subtraction ran, so the layer's
     * options can offer the switch and show the background itself.
     *
     * @param detector "C2" or "C3"
     * @param filter   FITS FILTER, e.g. "Orange" or "Clear"
     * @param polar    FITS POLAR, e.g. "Clear"
     * @param milli    the frame's observation time
     * @param roll     the frame's CROTA1 (or CROTA) in degrees, NaN when the header has neither
     */
    public record Request(String detector, String filter, String polar, long milli, int width, int height, double roll) {

        /** The request for a LASCO frame's header, or null when NRL publishes no background for it. */
        @Nullable
        public static Request of(Header header, int width, int height) {
            String telescope = header.getStringValue("TELESCOP");
            if (telescope == null || !telescope.trim().equalsIgnoreCase("SOHO"))
                return null;
            if (header.getBooleanValue(BackgroundFiles.MARK, false))
                return null; // a background Studio wrote (Show as layer) is not a frame to take one off
            String detector = header.getStringValue("DETECTOR");
            String filter = header.getStringValue("FILTER");
            String polar = header.getStringValue("POLAR");
            if (detector == null || filter == null || polar == null || productKey(detector, filter, polar) == null)
                return null;
            // LASCO splits the observation time across DATE-OBS and TIME-OBS and writes the date with
            // slashes, the same form FitsMetaData reassembles for its own use.
            String date = header.getStringValue("DATE-OBS");
            String time = header.getStringValue("TIME-OBS");
            if (date == null || time == null)
                return null;
            try {
                String hms = time.trim();
                long milli = TimeUtils.parse(date.trim().replace('/', '-') + 'T' + (hms.length() > 8 ? hms.substring(0, 8) : hms));
                return new Request(detector.trim(), filter.trim(), polar.trim(), milli, width, height, LascoBackground.roll(header));
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** The NRL background, interpolated, in DN per second, rows in FITS order; see {@link LascoBackground#perSecond}. */
        @Nullable
        public float[] perSecond() {
            return LascoBackground.perSecond(detector, filter, polar, milli, width * height);
        }

        /**
         * The background this choice gives for this frame, or null when there is nothing to subtract
         * (NRL lists nothing for the product). Throws {@link Unavailable} when NRL cannot be reached
         * and {@link Refused} when a local file cannot be used.
         */
        @Nullable
        public Result resolve(Choice choice) {
            return switch (choice.mode()) {
                case INTERPOLATED -> wrap(perSecond());
                case NEAREST -> wrap(LascoBackground.perSecond(detector, filter, polar, milli, width * height, true));
                case LOCAL_FILE -> LocalBackground.single(this, choice.fileA());
                case LOCAL_PAIR -> LocalBackground.pair(this, choice.fileA(), choice.fileB());
            };
        }

        @Nullable
        private static Result wrap(@Nullable float[] perSecond) {
            return perSecond == null ? null : new Result(perSecond, null);
        }

        /** The file or files this frame's background comes from, for a caption; empty if none is listed. */
        public String describe(Choice choice) throws Exception {
            if (choice.mode().local())
                return LocalBackground.describe(this, choice);
            String key = productKey(detector, filter, polar);
            if (key == null)
                return "";
            NavigableMap<Long, String> catalog = catalog(key);
            Map.Entry<Long, String> before = catalog.floorEntry(milli);
            Map.Entry<Long, String> after = catalog.ceilingEntry(milli);
            if (before == null && after == null)
                return "";
            if (choice.mode() == Mode.NEAREST)
                return nearest(before, after, milli).getValue();
            if (before == null || after == null || before.getKey().equals(after.getKey()))
                return (before == null ? after : before).getValue();
            return String.format(Locale.US, "%s and %s, weighted %.0f%% toward the later",
                    before.getValue(), after.getValue(), 100 * weight(before.getKey(), after.getKey(), milli));
        }
    }

    /** CROTA1, else CROTA, in degrees; NaN when the header carries neither. */
    static double roll(Header header) {
        double v = header.getDoubleValue("CROTA1", Double.NaN);
        return Double.isNaN(v) ? header.getDoubleValue("CROTA", Double.NaN) : v;
    }

    /** How far t lies from a toward b, clamped to [0, 1]: the later image's share in a linear blend. */
    static double weight(long a, long b, long t) {
        return b == a ? 0 : Math.clamp((t - a) / (double) (b - a), 0, 1);
    }

    /** Of two catalog entries around t (either may be null, not both), the closer; ties go to the earlier. */
    static <T> Map.Entry<Long, T> nearest(@Nullable Map.Entry<Long, T> before, @Nullable Map.Entry<Long, T> after, long t) {
        if (before == null)
            return after;
        if (after == null)
            return before;
        return t - before.getKey() <= after.getKey() - t ? before : after;
    }

    /** Listing per product key, fetched once. */
    private static final Map<String, NavigableMap<Long, String>> catalogs = new HashMap<>();
    /**
     * Decoded backgrounds in DN per second, by filename, most recently used last.
     *
     * <p>Bounded because each is four megabytes as floats and a movie spanning a season walks
     * through a new pair every week; a movie needs only the two bracketing whatever frame is
     * being decoded, and playback revisits them constantly, so a handful is plenty.
     */
    // Sixty-four (256 MB), not eight. Eight covered C2 and C3 bracketing one week boundary (four
    // slots had cost 99 loads of 6 files for one short movie, 2026-09-01), but a movie's frames are
    // built in parallel and out of time order when it loads, and prefetch decodes ahead across weeks,
    // so a two-month C2+C3 session reloaded backgrounds 2,423 times (2026-09-28).
    private static final int MAX_CACHED = 64;
    private static final Map<String, float[]> images = new LinkedHashMap<>(8, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > MAX_CACHED;
        }
    };

    /**
     * A background this frame should have had could not be fetched right now.
     *
     * <p>Distinct from the null return, which means there is nothing to subtract for this product
     * and the frame is complete as it is. A frame decoded under this exception is missing its
     * correction through no fault of its own, and the caller must not cache it as if it were done:
     * that is how a two-minute network outage used to leave a whole LASCO movie unsubtracted for
     * the rest of the session, with the server back and the log saying so.
     */
    public static final class Unavailable extends RuntimeException {
        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // After a failure, do not go back to the network for every frame of a playing movie: the
    // decoders would each block on the same unreachable host. Fail fast for a while instead; the
    // uncached frames retry on their own once this passes.
    private static final long RETRY_AFTER_MILLI = 30_000;
    private static volatile long failedUntil;

    // One lock per background file while it is fetched and decoded, so different files load side by
    // side and a frame whose pair is already cached never waits on another frame's fetch. This used
    // to be a single lock over perSecond, which serialized every LASCO decode behind every fetch.
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> loading = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The background for one frame, in DN per second, or null when there is nothing to subtract.
     *
     * @param detector "C2" or "C3"
     * @param filter   FITS FILTER, e.g. "Orange" or "Clear"
     * @param polar    FITS POLAR, e.g. "Clear"
     * @param milli    the frame's observation time
     * @param pixels   how many pixels the frame has, so a mismatched background is refused
     */
    @Nullable
    public static float[] perSecond(String detector, String filter, String polar, long milli, int pixels) {
        return perSecond(detector, filter, polar, milli, pixels, false);
    }

    /** As above; with nearest, the single monthly image whose date is closest rather than the blend of the two around it. */
    @Nullable
    public static float[] perSecond(String detector, String filter, String polar, long milli, int pixels, boolean nearest) {
        String key = productKey(detector, filter, polar);
        if (key == null)
            return null;
        if (System.currentTimeMillis() < failedUntil)
            throw new Unavailable("LASCO background fetch is backing off after a failure", null);
        // Two attempts. The decoders share the common pool with work that gets cancelled, and a
        // cancellation's interrupt flag can be left set on the worker that picks up a live frame
        // next, which then fails its first read with an InterruptedIOException. Clearing the flag
        // and trying once more keeps that frame from being quietly written without its background.
        for (int attempt = 0; ; attempt++) {
            try {
                return blend(key, milli, pixels, nearest);
            } catch (Exception e) {
                boolean interrupted = Thread.interrupted() | wasInterrupted(e);
                if (interrupted && attempt == 0)
                    continue;
                // A background that cannot be fetched is not a reason to fail the frame: the layer
                // still shows, uncorrected. But it is a reason not to remember that frame as done.
                Log.warn("LASCO background unavailable for " + detector + " " + TimeUtils.format(milli), e);
                failedUntil = System.currentTimeMillis() + RETRY_AFTER_MILLI;
                throw new Unavailable("LASCO background unavailable for " + detector, e);
            }
        }
    }

    private static boolean wasInterrupted(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof InterruptedException || t instanceof java.io.InterruptedIOException
                    || t instanceof java.nio.channels.ClosedByInterruptException)
                return true;
        return false;
    }

    @Nullable
    private static float[] blend(String key, long milli, int pixels, boolean nearest) throws Exception {
        {
            NavigableMap<Long, String> catalog = catalog(key);
            if (catalog.isEmpty())
                return null;

            Map.Entry<Long, String> before = catalog.floorEntry(milli);
            Map.Entry<Long, String> after = catalog.ceilingEntry(milli);
            if (before == null && after == null)
                return null;
            if (nearest)
                return image(nearest(before, after, milli).getValue(), pixels);
            if (before == null)
                before = after;
            if (after == null)
                after = before;

            float[] first = image(before.getValue(), pixels);
            if (first == null)
                return null;
            if (before.getKey().equals(after.getKey()))
                return first;
            float[] second = image(after.getValue(), pixels);
            if (second == null)
                return first;

            return mix(first, second, weight(before.getKey(), after.getKey(), milli));
        }
    }

    /** first * (1 - w) + second * w, pixel by pixel. */
    static float[] mix(float[] first, float[] second, double w) {
        float[] out = new float[first.length];
        for (int i = 0; i < out.length; i++)
            out[i] = (float) (first[i] * (1 - w) + second[i] * w);
        return out;
    }

    /** {@code 3m_clcl} style key, or null for a configuration with no published background. */
    @Nullable
    private static String productKey(String detector, String filter, String polar) {
        String telescope = switch (detector.trim().toUpperCase(Locale.US)) {
            case "C2" -> "2";
            case "C3" -> "3";
            default -> null;
        };
        String f = abbreviate(filter);
        String p = abbreviate(polar);
        return telescope == null || f == null || p == null ? null : telescope + "m_" + f + p;
    }

    /** The abbreviations NRL's abbrv_filpol.pro produces, as seen in the published filenames. */
    @Nullable
    private static String abbreviate(String name) {
        return switch (name.trim().toLowerCase(Locale.US)) {
            case "clear" -> "cl";
            case "orange" -> "or";
            case "blue" -> "bl";
            case "deeprd", "deep red", "deepred" -> "rd";
            case "ir" -> "ir";
            default -> null; // polarizer sequences and the rest have no monthly minimum published
        };
    }

    private static NavigableMap<Long, String> catalog(String key) throws Exception {
        synchronized (catalogs) { // once per product; holding it across the index fetch keeps that to one fetch
            return catalogLocked(key);
        }
    }

    private static NavigableMap<Long, String> catalogLocked(String key) throws Exception {
        NavigableMap<Long, String> cached = catalogs.get(key);
        if (cached != null)
            return cached;

        NavigableMap<Long, String> found = new TreeMap<>();
        String html = readIndex();
        Matcher m = ENTRY.matcher(html);
        while (m.find()) {
            String entryKey = m.group(1) + "m_" + m.group(2) + m.group(3);
            if (!entryKey.equals(key))
                continue;
            long milli = parseDate(m.group(4));
            if (milli > 0)
                found.put(milli, m.group(0));
        }
        Log.info("LASCO background catalog " + key + ": " + found.size() + " images");
        catalogs.put(key, found);
        return found;
    }

    /** yymmdd, where the archive starts in 1996 and runs to the present. */
    private static long parseDate(String yymmdd) {
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int year = yy >= CENTURY_PIVOT ? 1900 + yy : 2000 + yy;
        try {
            return TimeUtils.parse(String.format("%04d-%s-%sT00:00:00", year, yymmdd.substring(2, 4), yymmdd.substring(4, 6)));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String readIndex() throws Exception {
        try (NetClient nc = NetClient.of(URI.create(BASE_URL), true, NetClient.NetCache.CACHE)) {
            if (!nc.isSuccessful())
                throw new Exception("LASCO background index not available");
            return nc.getSource().readUtf8();
        }
    }

    /** Background pixels divided by their own exposure, so a frame can subtract per second. */
    @Nullable
    private static float[] image(String name, int pixels) throws Exception {
        float[] cached;
        synchronized (images) {
            cached = images.get(name);
        }
        if (cached != null)
            return cached.length == pixels ? cached : null;
        synchronized (loading.computeIfAbsent(name, k -> new Object())) {
            synchronized (images) {
                cached = images.get(name); // loaded by whoever held this file's lock before us
            }
            if (cached != null)
                return cached.length == pixels ? cached : null;
            return load(name, pixels);
        }
    }

    @Nullable
    private static float[] load(String name, int pixels) throws Exception {
        float[] data;
        try (NetClient nc = NetClient.of(URI.create(BASE_URL + name), true, NetClient.NetCache.CACHE)) {
            if (!nc.isSuccessful())
                return null;
            try (Fits fits = new Fits(nc.getStream())) {
                BasicHDU<?> hdu = fits.getHDU(0);
                if (hdu == null)
                    return null;
                double exposure = hdu.getHeader().getDoubleValue("EXPTIME", 0);
                if (!(exposure > 0))
                    return null;
                double bzero = hdu.getHeader().getDoubleValue("BZERO", 0);
                double bscale = hdu.getHeader().getDoubleValue("BSCALE", 1);
                data = flatten(hdu.getKernel(), pixels, bzero, bscale, 0, exposure); // the monthly images carry no bias
            }
        }
        if (data == null)
            return null;
        synchronized (images) {
            images.put(name, data);
        }
        Log.info("LASCO background " + name + " loaded");
        return data;
    }

    /** Rows in FITS order, matching the frame array the caller will subtract this from: (BZERO + raw * BSCALE - offset) / exposure. */
    @Nullable
    static float[] flatten(Object kernel, int pixels, double bzero, double bscale, double offset, double exposure) {
        if (!(kernel instanceof Object[] rows))
            return null;
        float[] out = new float[pixels];
        int at = 0;
        for (Object row : rows) {
            int written = switch (row) {
                case short[] r -> copy(r, out, at, bzero, bscale, offset, exposure);
                case int[] r -> copy(r, out, at, bzero, bscale, offset, exposure);
                case float[] r -> copy(r, out, at, bzero, bscale, offset, exposure);
                case double[] r -> copy(r, out, at, bzero, bscale, offset, exposure);
                default -> -1;
            };
            if (written < 0)
                return null;
            at += written;
        }
        return at == pixels ? out : null;
    }

    private static int copy(short[] row, float[] out, int at, double bzero, double bscale, double offset, double exposure) {
        int n = Math.min(row.length, out.length - at);
        for (int i = 0; i < n; i++)
            out[at + i] = (float) ((bzero + row[i] * bscale - offset) / exposure);
        return n;
    }

    private static int copy(int[] row, float[] out, int at, double bzero, double bscale, double offset, double exposure) {
        int n = Math.min(row.length, out.length - at);
        for (int i = 0; i < n; i++)
            out[at + i] = (float) ((bzero + row[i] * bscale - offset) / exposure);
        return n;
    }

    private static int copy(float[] row, float[] out, int at, double bzero, double bscale, double offset, double exposure) {
        int n = Math.min(row.length, out.length - at);
        for (int i = 0; i < n; i++)
            out[at + i] = (float) ((bzero + row[i] * bscale - offset) / exposure);
        return n;
    }

    private static int copy(double[] row, float[] out, int at, double bzero, double bscale, double offset, double exposure) {
        int n = Math.min(row.length, out.length - at);
        for (int i = 0; i < n; i++)
            out[at + i] = (float) ((bzero + row[i] * bscale - offset) / exposure);
        return n;
    }

    private LascoBackground() {
    }

}
