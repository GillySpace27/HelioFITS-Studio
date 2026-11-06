package org.helioviewer.jhv.io;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

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
    static final int MAX_STEPS = 2000;
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

}
