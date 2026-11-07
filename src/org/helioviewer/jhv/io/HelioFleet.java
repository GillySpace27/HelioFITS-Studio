package org.helioviewer.jhv.io;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * HelioFleet (Soumyaranjan Dash, NSO; MIT licence): every sr-dash address Studio uses is here.
 *
 * <p>HelioFleet publishes JPL Horizons vectors for the heliophysics fleet since 2010, refreshed
 * monthly, as static JSON: {@code data/manifest.json} gives each body's span, step, units and years,
 * and {@code data/<body>/<year>.json} holds {@code t0} (Julian date, UT), {@code step} (days),
 * {@code n} and {@code d}, a flat x, y, z array in the J2000 ecliptic frame, heliocentric in au or,
 * for the {@code *_geo} bodies (the L1 monitors), geocentric in km. Format as documented at
 * https://sr-dash.github.io/heliofleet/llms.txt.
 */
public final class HelioFleet {

    public static final String BASE = "https://sr-dash.github.io/heliofleet/";
    public static final String SITE = "https://sr-dash.github.io/";

    private static final double AU_KM = 149597870.7;
    private static final double JD_UNIX = 2440587.5;

    private HelioFleet() {}

    /** Samples of one body over a time range: times in Unix milliseconds, positions in km. */
    public record Samples(long[] milli, double[] km, boolean geocentric) {}

    /**
     * Every sample of {@code body} from a little before {@code start} to a little after {@code end}
     * (two steps either side, so the ends can be interpolated). Off the event thread.
     */
    public static Samples fetch(String body, long start, long end) throws IOException {
        try {
            JSONObject manifest = json(BASE + "data/manifest.json");
            JSONObject b = manifest.getJSONObject("bodies").optJSONObject(body);
            if (b == null)
                throw new IOException("HelioFleet has no body " + body);
            double step = b.getDouble("step");
            boolean geocentric = "earth".equals(b.optString("center"));
            double scale = "au".equals(b.optString("units")) ? AU_KM : 1;
            double jd0 = Math.max(jd(start) - 2 * step, b.getDouble("start"));
            double jd1 = Math.min(jd(end) + 2 * step, b.getDouble("end"));
            if (jd1 < jd0)
                throw new IOException("HelioFleet's " + body + " positions run from " + date(b.getDouble("start")) + " to " + date(b.getDouble("end")));

            JSONArray years = b.getJSONArray("years");
            String version = manifest.optString("generated", "");
            SampleList out = new SampleList();
            for (int y = year(jd0); y <= year(jd1); y++) {
                if (!contains(years, y))
                    continue;
                JSONObject chunk = json(BASE + "data/" + body + "/" + y + ".json" + (version.isEmpty() ? "" : "?v=" + java.net.URLEncoder.encode(version, java.nio.charset.StandardCharsets.UTF_8)));
                double t0 = chunk.getDouble("t0"), dt = chunk.getDouble("step");
                int n = chunk.getInt("n");
                JSONArray d = chunk.getJSONArray("d");
                for (int i = 0; i < n; i++) {
                    double jd = t0 + i * dt;
                    if (jd >= jd0 && jd <= jd1)
                        out.add(milli(jd), d.getDouble(3 * i) * scale, d.getDouble(3 * i + 1) * scale, d.getDouble(3 * i + 2) * scale);
                }
            }
            if (out.size < 2)
                throw new IOException("HelioFleet returned no " + body + " positions for that time");
            return out.samples(geocentric);
        } catch (JSONException e) {
            throw new IOException("Unexpected HelioFleet data: " + e.getMessage(), e);
        }
    }

    private static JSONObject json(String url) throws IOException {
        try (NetClient nc = NetClient.of(URI.create(url), false, NetClient.NetCache.NETWORK)) {
            return new JSONObject(new String(nc.getStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static boolean contains(JSONArray a, int v) {
        for (int i = 0; i < a.length(); i++)
            if (a.optInt(i, Integer.MIN_VALUE) == v)
                return true;
        return false;
    }

    static double jd(long milli) {
        return milli / 864e5 + JD_UNIX;
    }

    static long milli(double jd) {
        return Math.round((jd - JD_UNIX) * 864e5);
    }

    private static int year(double jd) {
        return Instant.ofEpochMilli(milli(jd)).atZone(ZoneOffset.UTC).getYear();
    }

    private static String date(double jd) {
        return Instant.ofEpochMilli(milli(jd)).toString().substring(0, 10);
    }

    /** A growing run of samples, without boxing. */
    static final class SampleList {
        long[] t = new long[256];
        double[] v = new double[768];
        int size;

        void add(long milli, double x, double y, double z) {
            if (size == t.length) {
                t = java.util.Arrays.copyOf(t, size * 2);
                v = java.util.Arrays.copyOf(v, size * 6);
            }
            t[size] = milli;
            v[3 * size] = x;
            v[3 * size + 1] = y;
            v[3 * size + 2] = z;
            size++;
        }

        Samples samples(boolean geocentric) {
            return new Samples(java.util.Arrays.copyOf(t, size), java.util.Arrays.copyOf(v, 3 * size), geocentric);
        }
    }

}
