package org.helioviewer.jhv.metadata;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.json.JSONArray;
import org.json.JSONObject;

import org.helioviewer.jhv.app.Log;

/**
 * Pointing for native LASCO frames whose header has none.
 *
 * <p>NRL's level-0.5 C2 headers from 2025-08-21 15:48 to 2025-08-31 23:36 have CROTA1 = CROTA2 = 0 and
 * CRPIX at the array centre (512.5 for a full frame, 256.5 for a binned one): the pointing was never filled
 * in. SOHO was rolled the whole time (the real CROTA runs -177.25 to -178.58 across the gap, and C3 and
 * Helioviewer agree), so taking the zeros at face value shows those frames 178 degrees off.
 *
 * <p>LascoClient reads every header before a movie loads. Each such frame takes CROTA from the frame with
 * pointing nearest in time, from its own telescope or the other one (C2 and C3 share SOHO's roll, less a
 * fixed mounting offset), and CRPIX only from a frame of its own telescope and size, since CRPIX is in that
 * frame's pixels.
 */
public final class LascoPointing {

    public record Frame(String name, String detector, String dateObs, String timeObs, long milli, long width, long height,
                        double crota, double crpix1, double crpix2, boolean placeholder) {

        boolean lendsRoll() {
            return !placeholder && Double.isFinite(crota);
        }
    }

    private record Pointing(double crota, double crpix1, double crpix2, String from) {}

    // C2 CROTA minus C3 CROTA at the same moment: +0.732 to +0.734 deg on NRL headers from 2025-08-15 to
    // 2025-09-06, +0.729 to +0.730 on Helioviewer's for 2025-08-20, 2026-07-01 and 2026-09-14
    private static final double C2_MINUS_C3 = 0.732;

    private static final Map<String, Pointing> lent = new ConcurrentHashMap<>();
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    public static boolean isPlaceholder(double crota1, double crota2, double crpix1, double crpix2, long width, long height) {
        return crota1 == 0 && crota2 == 0 && crpix1 == (width + 1) / 2. && crpix2 == (height + 1) / 2.;
    }

    // ponytail: nearest lender, not interpolated. CROTA drifts about 0.12 deg/day, so this is close enough
    // whenever either telescope has a frame within hours; interpolate if longer gaps in both ever appear.
    public static void lend(@Nonnull List<Frame> frames, @Nonnull List<Frame> otherTelescope) {
        List<Frame> own = frames.stream().filter(Frame::lendsRoll).toList();
        List<Frame> other = otherTelescope.stream().filter(Frame::lendsRoll).toList();
        for (Frame f : frames) {
            if (!f.placeholder())
                continue;
            String key = key(f.detector(), f.dateObs(), f.timeObs());
            Optional<Frame> roll = nearest(Stream.concat(own.stream(), other.stream()), f);
            if (roll.isEmpty()) {
                Log.warn("LASCO " + f.name() + " " + key + ": no pointing in the header and no frame of either telescope in this request"
                        + " to borrow from; it will show unrotated, possibly 180 degrees off");
                continue;
            }
            Optional<Frame> pixels = nearest(own.stream().filter(l -> l.width() == f.width() && l.height() == f.height()
                    && Double.isFinite(l.crpix1()) && Double.isFinite(l.crpix2())), f);

            Frame r = roll.get();
            double offset = offset(f.detector(), r.detector());
            double crota = r.crota() + offset;
            lent.put(key, new Pointing(crota, pixels.map(Frame::crpix1).orElse(Double.NaN), pixels.map(Frame::crpix2).orElse(Double.NaN),
                    norm(r.detector()) + ' ' + r.name()));
            Log.info(String.format(Locale.ROOT, "LASCO %s %s: no pointing in the header (CROTA 0, CRPIX at the image centre); borrowing CROTA %.3f from %s %s%s, %.1f h away; %s",
                    f.name(), key, crota, norm(r.detector()), r.name(),
                    offset == 0 ? "" : String.format(Locale.ROOT, " (%.3f %+.3f mounting offset)", r.crota(), offset), hours(r, f),
                    pixels.map(p -> String.format(Locale.ROOT, "CRPIX (%.1f, %.1f) from %s, %.1f h away", p.crpix1(), p.crpix2(), p.name(), hours(p, f)))
                            .orElse("CRPIX left at the image centre: no " + norm(f.detector()) + ' ' + f.width() + 'x' + f.height()
                                    + " frame with pointing in this request")));
        }
    }

    @Nonnull
    static MetaDataContainer fill(@Nonnull MetaDataContainer m) {
        if (!m.getString("INSTRUME").map(String::trim).orElse("").equals("LASCO") || m.getString("HV_SOURCE_PROGRAM").isPresent())
            return m;
        if (!isPlaceholder(m.getDouble("CROTA1").orElse(Double.NaN), m.getDouble("CROTA2").orElse(Double.NaN),
                m.getDouble("CRPIX1").orElse(Double.NaN), m.getDouble("CRPIX2").orElse(Double.NaN),
                m.getLong("NAXIS1").orElse(0L), m.getLong("NAXIS2").orElse(0L)))
            return m;

        String key = key(m.getString("DETECTOR").orElse(""), m.getString("DATE-OBS").orElse(""),
                m.getString("TIME_OBS").or(() -> m.getString("TIME-OBS")).orElse(""));
        Pointing p = lent.get(key);
        if (p == null) {
            if (reported.add(key + " missing"))
                Log.warn("LASCO " + key + ": no pointing in the header and none borrowed; showing it unrotated, possibly 180 degrees off");
            return m;
        }
        if (reported.add(key + " lent"))
            Log.info(String.format(Locale.ROOT, "LASCO %s: using borrowed CROTA %.3f from %s, %s", key, p.crota(), p.from(),
                    Double.isNaN(p.crpix1()) ? "CRPIX left at the image centre" : String.format(Locale.ROOT, "CRPIX (%.1f, %.1f)", p.crpix1(), p.crpix2())));
        return new Filled(m, p);
    }

    /**
     * The table so far, for the session file, narrowed to one telescope.
     *
     * <p>Lending needs a header probe of every frame, which a session restored from its cached URI
     * list has no other reason to do. Saving what the probe concluded makes the restore free: the
     * keys name a frame exactly (detector, date, time), so an entry can only ever match the frame it
     * came from and carrying the whole table costs nothing but bytes.
     */
    @Nonnull
    public static JSONObject toJson(String detector) {
        String prefix = norm(detector) + ' ';
        JSONObject jo = new JSONObject();
        lent.forEach((key, p) -> {
            if (key.startsWith(prefix))
                // CRPIX is NaN when no frame of the right size could lend it, and JSON has no NaN.
                jo.put(key, new JSONArray().put(p.crota()).put(num(p.crpix1())).put(num(p.crpix2())).put(p.from()));
        });
        return jo;
    }

    /** Put a saved table back, without displacing anything this process worked out for itself. */
    public static void restore(@Nonnull JSONObject jo) {
        for (String key : jo.keySet()) {
            JSONArray a = jo.optJSONArray(key);
            if (a != null && a.length() == 4)
                lent.putIfAbsent(key, new Pointing(a.getDouble(0), dbl(a, 1), dbl(a, 2), a.getString(3)));
        }
    }

    /** Drop the table, so a check can stand in a fresh process. Package-private: nothing else wants it. */
    static void forget() {
        lent.clear();
        reported.clear();
    }

    private static Object num(double d) {
        return Double.isNaN(d) ? JSONObject.NULL : d;
    }

    private static double dbl(JSONArray a, int i) {
        return a.isNull(i) ? Double.NaN : a.getDouble(i);
    }

    private static Optional<Frame> nearest(Stream<Frame> lenders, Frame f) {
        return lenders.min(Comparator.comparingLong(l -> Math.abs(l.milli() - f.milli())));
    }

    private static double offset(String to, String from) {
        String t = norm(to), s = norm(from);
        if (t.equals("C2") && s.equals("C3"))
            return C2_MINUS_C3;
        if (t.equals("C3") && s.equals("C2"))
            return -C2_MINUS_C3;
        return 0;
    }

    private static double hours(Frame a, Frame b) {
        return Math.abs(a.milli() - b.milli()) / 3.6e6;
    }

    private static String norm(String detector) {
        return detector.trim().toUpperCase(Locale.ROOT);
    }

    private static String key(String detector, String date, String time) {
        return norm(detector) + ' ' + date.trim() + ' ' + time.trim();
    }

    private record Filled(MetaDataContainer m, Pointing p) implements MetaDataContainer {

        @Nonnull
        @Override
        public Optional<Double> getDouble(String key) {
            return switch (key) {
                case "CROTA", "CROTA1", "CROTA2" -> Optional.of(p.crota());
                case "CRPIX1" -> Double.isNaN(p.crpix1()) ? m.getDouble(key) : Optional.of(p.crpix1());
                case "CRPIX2" -> Double.isNaN(p.crpix2()) ? m.getDouble(key) : Optional.of(p.crpix2());
                default -> m.getDouble(key);
            };
        }

        @Nonnull
        @Override
        public Optional<String> getString(String key) {
            return switch (key) {
                case "CROTA", "CROTA1", "CROTA2", "CRPIX1", "CRPIX2" -> getDouble(key).map(String::valueOf);
                default -> m.getString(key);
            };
        }

        @Nonnull
        @Override
        public Optional<Long> getLong(String key) {
            return m.getLong(key);
        }

        @Nonnull
        @Override
        public String getRequiredString(String key) {
            return getString(key).orElseGet(() -> m.getRequiredString(key));
        }

        @Override
        public long getRequiredLong(String key) {
            return m.getRequiredLong(key);
        }

        @Override
        public double getRequiredDouble(String key) {
            return getDouble(key).orElseGet(() -> m.getRequiredDouble(key));
        }
    }

    private LascoPointing() {}
}
