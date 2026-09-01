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

    private static boolean enabled() {
        return !"false".equals(Settings.getProperty("display.lascoBackground"));
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
    // Eight, not four: C2 and C3 in one movie bracket up to six files across a week boundary,
    // and with four slots the two detectors' frames evicted each other's pair on every decode
    // (99 loads of 6 files for one short movie, measured 2026-09-01).
    private static final int MAX_CACHED = 8;
    private static final Map<String, float[]> images = new LinkedHashMap<>(8, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > MAX_CACHED;
        }
    };

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
    public static synchronized float[] perSecond(String detector, String filter, String polar, long milli, int pixels) {
        if (!enabled())
            return null;

        String key = productKey(detector, filter, polar);
        if (key == null)
            return null;
        // Two attempts. The decoders share the common pool with work that gets cancelled, and a
        // cancellation's interrupt flag can be left set on the worker that picks up a live frame
        // next, which then fails its first read with an InterruptedIOException. Clearing the flag
        // and trying once more keeps that frame from being quietly written without its background.
        for (int attempt = 0; ; attempt++) {
            try {
                return blend(key, milli, pixels);
            } catch (Exception e) {
                boolean interrupted = Thread.interrupted() | wasInterrupted(e);
                if (interrupted && attempt == 0)
                    continue;
                // A background that cannot be fetched is not a reason to fail the frame: the layer
                // still shows, uncorrected, which is what it did before this existed.
                Log.warn("LASCO background unavailable for " + detector + " " + TimeUtils.format(milli), e);
                return null;
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
    private static float[] blend(String key, long milli, int pixels) throws Exception {
        {
            NavigableMap<Long, String> catalog = catalog(key);
            if (catalog.isEmpty())
                return null;

            Map.Entry<Long, String> before = catalog.floorEntry(milli);
            Map.Entry<Long, String> after = catalog.ceilingEntry(milli);
            if (before == null && after == null)
                return null;
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

            double span = after.getKey() - before.getKey();
            double w = Math.clamp((milli - before.getKey()) / span, 0, 1);
            float[] out = new float[pixels];
            for (int i = 0; i < pixels; i++)
                out[i] = (float) (first[i] * (1 - w) + second[i] * w);
            return out;
        }
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

    /** Background pixels divided by their own exposure, so a frame can subtract per second. Called under perSecond's lock. */
    @Nullable
    private static float[] image(String name, int pixels) throws Exception {
        float[] cached = images.get(name);
        if (cached != null)
            return cached.length == pixels ? cached : null;

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
                data = flatten(hdu.getKernel(), pixels, bzero, bscale, exposure);
            }
        }
        if (data == null)
            return null;
        images.put(name, data);
        Log.info("LASCO background " + name + " loaded");
        return data;
    }

    /** Rows in FITS order, matching the frame array the caller will subtract this from. */
    @Nullable
    private static float[] flatten(Object kernel, int pixels, double bzero, double bscale, double exposure) {
        if (!(kernel instanceof Object[] rows))
            return null;
        float[] out = new float[pixels];
        int at = 0;
        for (Object row : rows) {
            int written = switch (row) {
                case short[] r -> copy(r, out, at, bzero, bscale, exposure);
                case int[] r -> copy(r, out, at, bzero, bscale, exposure);
                case float[] r -> copy(r, out, at, bzero, bscale, exposure);
                case double[] r -> copy(r, out, at, bzero, bscale, exposure);
                default -> -1;
            };
            if (written < 0)
                return null;
            at += written;
        }
        return at == pixels ? out : null;
    }

    private static int copy(short[] row, float[] out, int at, double bzero, double bscale, double exposure) {
        int n = Math.min(row.length, out.length - at);
        for (int i = 0; i < n; i++)
            out[at + i] = (float) ((bzero + row[i] * bscale) / exposure);
        return n;
    }

    private static int copy(int[] row, float[] out, int at, double bzero, double bscale, double exposure) {
        int n = Math.min(row.length, out.length - at);
        for (int i = 0; i < n; i++)
            out[at + i] = (float) ((bzero + row[i] * bscale) / exposure);
        return n;
    }

    private static int copy(float[] row, float[] out, int at, double bzero, double bscale, double exposure) {
        int n = Math.min(row.length, out.length - at);
        for (int i = 0; i < n; i++)
            out[at + i] = (float) ((bzero + row[i] * bscale) / exposure);
        return n;
    }

    private static int copy(double[] row, float[] out, int at, double bzero, double bscale, double exposure) {
        int n = Math.min(row.length, out.length - at);
        for (int i = 0; i < n; i++)
            out[at + i] = (float) ((bzero + row[i] * bscale) / exposure);
        return n;
    }

    private LascoBackground() {
    }

}
