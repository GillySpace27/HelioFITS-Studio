package org.helioviewer.jhv.io;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Heliocentric positions of one body from the JPL Horizons API (https://ssd-api.jpl.nasa.gov/doc/horizons.html),
 * for spacecraft that neither Studio's kernels, the ROB position service nor HelioFleet cover (Ulysses,
 * MESSENGER). Geometric vectors from the Sun's centre in the J2000 ecliptic frame, in km, sampled at
 * UT; at most {@link #MAX_STEPS} intervals per request.
 */
public final class JplHorizons {

    public static final String API = "https://ssd.jpl.nasa.gov/api/horizons.api";
    public static final int MAX_STEPS = 2000; // Studio's own cap per request, not Horizons'
    private static final long HOUR = 3600_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private JplHorizons() {}

    /** {@code command} is a Horizons target, such as a NAIF id ("-55"). Off the event thread. */
    public static HelioFleet.Samples fetch(String command, long start, long end) throws IOException {
        long span = Math.max(end - start, 2 * HOUR);
        int steps = Math.clamp(span / HOUR, 2, MAX_STEPS);
        String url = API + "?format=json" + q("COMMAND", "'" + command + "'") + q("EPHEM_TYPE", "VECTORS") + q("CENTER", "'500@10'")
                + q("REF_PLANE", "ECLIPTIC") + q("REF_SYSTEM", "ICRF") + q("OUT_UNITS", "KM-S") + q("VEC_TABLE", "1") + q("VEC_CORR", "NONE")
                + q("CSV_FORMAT", "YES") + q("TIME_TYPE", "UT") + q("OBJ_DATA", "NO")
                + q("START_TIME", "'" + TIME.format(Instant.ofEpochMilli(start)) + "'") + q("STOP_TIME", "'" + TIME.format(Instant.ofEpochMilli(start + span)) + "'")
                + q("STEP_SIZE", "'" + steps + "'");
        String result;
        try (NetClient nc = NetClient.of(URI.create(url), false, NetClient.NetCache.NETWORK)) {
            result = new JSONObject(new String(nc.getStream().readAllBytes(), StandardCharsets.UTF_8)).getString("result");
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from JPL Horizons: " + e.getMessage(), e);
        }
        return parse(result);
    }

    private static String q(String key, String value) {
        return '&' + key + '=' + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The CSV vector table between $$SOE and $$EOE: JDUT, calendar date, X, Y, Z. */
    public static HelioFleet.Samples parse(String result) throws IOException {
        int soe = result.indexOf("$$SOE"), eoe = result.indexOf("$$EOE");
        if (soe < 0 || eoe < soe) {
            String why = result.lines().map(String::strip).filter(l -> l.startsWith("No ephemeris") || l.contains("No matches") || l.startsWith("Cannot"))
                    .findFirst().orElse("no vector table in the answer");
            throw new IOException("JPL Horizons: " + why);
        }
        HelioFleet.SampleList out = new HelioFleet.SampleList();
        for (String line : result.substring(soe + 5, eoe).strip().split("\n")) {
            String[] f = line.split(",");
            if (f.length < 5)
                continue;
            try {
                out.add(HelioFleet.milli(Double.parseDouble(f[0].strip())), Double.parseDouble(f[2].strip()), Double.parseDouble(f[3].strip()), Double.parseDouble(f[4].strip()));
            } catch (NumberFormatException e) {
                throw new IOException("JPL Horizons: unreadable line \"" + line.strip() + "\"", e);
            }
        }
        if (out.size < 2)
            throw new IOException("JPL Horizons returned fewer than two positions");
        return out.samples(false);
    }

    /** Heliocentric states: UT milliseconds, then x, y, z per sample in km and vx, vy, vz in km/s (J2000 ecliptic, ICRF). */
    public record States(long[] milli, double[] km, double[] kms) {}

    /**
     * A state-vector request (VEC_TABLE 2) on a fixed step of whole minutes, otherwise as {@link #fetch} asks.
     * extra/tools/capture_horizons_fixtures.py states_url builds the same string, byte for byte.
     */
    public static String statesUrl(String command, long start, long end, int stepMinutes) {
        return API + "?format=json" + q("COMMAND", "'" + command + "'") + q("EPHEM_TYPE", "VECTORS") + q("CENTER", "'500@10'")
                + q("REF_PLANE", "ECLIPTIC") + q("REF_SYSTEM", "ICRF") + q("OUT_UNITS", "KM-S") + q("VEC_TABLE", "2") + q("VEC_CORR", "NONE")
                + q("CSV_FORMAT", "YES") + q("TIME_TYPE", "UT") + q("OBJ_DATA", "NO")
                + q("START_TIME", "'" + TIME.format(Instant.ofEpochMilli(start)) + "'") + q("STOP_TIME", "'" + TIME.format(Instant.ofEpochMilli(end)) + "'")
                + q("STEP_SIZE", "'" + stepMinutes + " m'");
    }

    /** Off the event thread. */
    public static States fetchStates(String command, long start, long end, int stepMinutes) throws IOException {
        return parseStates(fetchText(statesUrl(command, start, end, stepMinutes)));
    }

    /** The "result" text of a JSON-format Horizons request, as it came; for callers that keep it. Off the event thread. */
    public static String fetchText(String url) throws IOException {
        try (NetClient nc = NetClient.of(URI.create(url), false, NetClient.NetCache.NETWORK)) {
            return new JSONObject(new String(nc.getStream().readAllBytes(), StandardCharsets.UTF_8)).getString("result");
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from JPL Horizons: " + e.getMessage(), e);
        }
    }

    /** The CSV state table between $$SOE and $$EOE; a refusal becomes an IOException carrying Horizons' line. */
    public static States parseStates(String result) throws IOException {
        int soe = result.indexOf("$$SOE"), eoe = result.indexOf("$$EOE");
        if (soe < 0 || eoe < soe) {
            String why = result.lines().map(String::strip).filter(l -> l.startsWith("No ephemeris") || l.contains("No matches") || l.startsWith("Cannot"))
                    .findFirst().orElse("no vector table in the answer");
            throw new IOException("JPL Horizons: " + why);
        }
        String[] lines = result.substring(soe + 5, eoe).strip().split("\\R");
        long[] milli = new long[lines.length];
        double[] km = new double[3 * lines.length], kms = new double[3 * lines.length];
        int n = 0;
        for (String line : lines) {
            if (line.isBlank())
                continue;
            try {
                n = stateRow(line.split(","), n, milli, km, kms);
            } catch (NumberFormatException e) {
                throw new IOException("JPL Horizons: unreadable line \"" + line.strip() + "\"", e);
            }
        }
        if (n < 2)
            throw new IOException("JPL Horizons returned fewer than two states");
        return new States(Arrays.copyOf(milli, n), Arrays.copyOf(km, 3 * n), Arrays.copyOf(kms, 3 * n));
    }

    // The one place that knows the VEC_TABLE 2 CSV columns: JDUT, calendar date, X, Y, Z, VX, VY, VZ
    // (and a trailing comma). UNCHECKED against a real comet answer until the comet3d fixtures are
    // captured; if Horizons orders them otherwise, this is what changes.
    private static final int COL_JD = 0, COL_X = 2, COL_VX = 5, COLUMNS = 8;

    private static int stateRow(String[] f, int n, long[] milli, double[] km, double[] kms) throws IOException {
        if (f.length < COLUMNS)
            throw new IOException("JPL Horizons: expected " + COLUMNS + " columns (JDUT, date, X, Y, Z, VX, VY, VZ), got " + f.length);
        long t = HelioFleet.milli(Double.parseDouble(f[COL_JD].strip()));
        if (n > 0 && t <= milli[n - 1])
            throw new IOException("JPL Horizons: times out of order at JD " + f[COL_JD].strip());
        milli[n] = t;
        for (int k = 0; k < 3; k++) {
            km[3 * n + k] = Double.parseDouble(f[COL_X + k].strip());
            kms[3 * n + k] = Double.parseDouble(f[COL_VX + k].strip());
        }
        return n + 1;
    }

    /** The {@code {source: ...}} tag on the "Target body name" line (which orbit solution answered), or "". */
    public static String sourceTag(String result) {
        return result.lines().filter(l -> l.startsWith("Target body name")).findFirst().map(l -> {
            int a = l.indexOf("{source:"), b = a < 0 ? -1 : l.indexOf('}', a);
            return b < 0 ? "" : l.substring(a, b + 1);
        }).orElse("");
    }

}
