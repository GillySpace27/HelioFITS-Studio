package org.helioviewer.jhv.timelines.band;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.helioviewer.jhv.timelines.draw.YAxis;

import org.json.JSONArray;
import org.json.JSONObject;

import uk.ac.bristol.star.cdf.AttributeEntry;
import uk.ac.bristol.star.cdf.CdfContent;
import uk.ac.bristol.star.cdf.GlobalAttribute;
import uk.ac.bristol.star.cdf.Variable;
import uk.ac.bristol.star.cdf.VariableAttribute;

/**
 * Dedicated reader for the IMAP SWAPI L3a and MAG L2 daily CDFs (SPDF). The generic reader would plot the
 * flag variables (VAR_TYPE data) as bands and cannot place samples on a minute grid without inventing data,
 * so this one turns a file into per-minute arrays for its UTC day:
 * every sample is written to its floor minute (SWAPI epochs are window centres), samples whose flag
 * (swp_flags, quality_flags) is nonzero or whose value is fill are dropped, and a minute with no sample stays
 * YAxis.BLANK (no filling of gaps). Several samples in a minute (MAG at 2 Hz) give their mean.
 * Series names: SWAPI scalars keep the CDF variable name (proton_sw_speed_sun); RTN vectors get _R, _T, _N
 * (proton_sw_velocity_rtn_sun_R); MAG has "magnitude" and b_rtn_R, b_rtn_T, b_rtn_N or b_gse_x, b_gse_y, b_gse_z.
 */
final class ImapCdfReader {

    static final int MINUTES_PER_DAY = 1440;
    private static final long MINUTE_MS = 60_000L;
    private static final long DAY_MS = 86_400_000L;
    /** TT2000 ns since J2000 to Unix ms for dates after 2017-01-01 (TT-UTC = 32.184 s + 37 leap seconds). */
    private static final long TT2000_TO_UNIX_MS = 946_727_930_816L;

    /**
     * minutes: series name to 1440 floats (minute 0 = dayStartMs), BLANK where no valid sample.
     * units: series name to the CDF UNITS attribute. acknowledgement: global attribute, "" if absent.
     */
    record DayMinutes(String product, long dayStartMs, Map<String, float[]> minutes, Map<String, String> units,
                      String acknowledgement) {}

    private ImapCdfReader() {}

    /** UTC ms of a TT2000 value; valid only after 2017-01-01, a new leap second would shift it (ImapCdfCheck). */
    static long tt2000ToMs(long ns) {
        return Math.floorDiv(ns, 1_000_000L) + TT2000_TO_UNIX_MS;
    }

    static boolean isImap(CdfContent cdf) {
        String src = logicalSource(cdf);
        return src != null && src.startsWith("imap_");
    }

    /** Catalog product id for a Logical_source (swapi-proton, swapi-alpha, mag-rtn, mag-gse, mag-gsm) or null. */
    static String product(String logicalSource) {
        if (logicalSource == null)
            return null;
        return switch (logicalSource) {
            case "imap_swapi_l3a_proton-sw" -> "swapi-proton";
            case "imap_swapi_l3a_alpha-sw" -> "swapi-alpha";
            case "imap_mag_l2_norm-rtn" -> "mag-rtn";
            case "imap_mag_l2_norm-gse" -> "mag-gse";
            case "imap_mag_l2_norm-gsm" -> "mag-gsm";
            default -> null;
        };
    }

    /** Whole file as bands (CDF drag and drop): one BandData per series, only minutes that hold data. */
    static List<BandData> read(CdfContent cdf, URI uri) throws IOException {
        DayMinutes day = readDay(cdf, uri);
        List<BandData> ret = new ArrayList<>();
        for (Map.Entry<String, float[]> e : day.minutes().entrySet()) {
            float[] m = e.getValue();
            int n = 0;
            for (float v : m)
                if (v != YAxis.BLANK)
                    n++;
            long[] dates = new long[n];
            float[] values = new float[n];
            int k = 0;
            for (int i = 0; i < m.length; i++) {
                if (m[i] != YAxis.BLANK) {
                    dates[k] = day.dayStartMs() + i * MINUTE_MS;
                    values[k++] = m[i];
                }
            }
            ret.add(new BandData(new BandType(bandJson(day, e.getKey())), dates, values));
        }
        return ret;
    }

    private static JSONObject bandJson(DayMinutes day, String series) {
        String name = "IMAP " + day.product() + ' ' + series;
        boolean alpha = "swapi-alpha".equals(day.product());
        double min;
        double max;
        String scale = "linear";
        if (series.endsWith("density")) {
            min = alpha ? 0.001 : 0.1;
            max = alpha ? 10 : 100;
            scale = "logarithmic";
        } else if (series.endsWith("temperature")) {
            min = 1e4;
            max = 1e6;
            scale = "logarithmic";
        } else if (series.contains("speed")) {
            min = 250;
            max = 800;
        } else if (series.contains("velocity")) {
            min = -800;
            max = 800;
        } else if ("magnitude".equals(series)) {
            min = 0;
            max = 20;
        } else {
            min = -15;
            max = 15;
        }
        return new JSONObject().
                put("baseUrl", "imap:" + day.product() + '#' + series).
                put("unitLabel", day.units().getOrDefault(series, " ")).
                put("name", name).
                put("range", new JSONArray().put(min).put(max)).
                put("scale", scale).
                put("label", name);
    }

    static DayMinutes readDay(CdfContent cdf, URI uri) throws IOException {
        String source = logicalSource(cdf);
        String product = product(source);
        if (product == null)
            throw new IOException("Unsupported IMAP product " + source + ": " + uri);

        Map<String, Variable> vars = new LinkedHashMap<>();
        for (Variable v : cdf.getVariables())
            vars.putIfAbsent(v.getName().toLowerCase(java.util.Locale.ROOT), v);
        Variable epochVar = vars.get("epoch");
        if (epochVar == null)
            throw new IOException("Epoch not found: " + uri);
        long[] epoch = readEpoch(epochVar);

        boolean mag = product.startsWith("mag-");
        Variable flagVar = vars.get(mag ? "quality_flags" : "swp_flags");
        float[] flags = flagVar == null ? null : BandReaderCdf.readCDFVariableFloat(flagVar, Float.NaN)[0];
        if (flags != null && flags.length != epoch.length)
            throw new IOException("Inconsistent lengths of epoch and flags: " + uri);

        // series to (variable, axis)
        Map<String, Object[]> wanted = new LinkedHashMap<>();
        if (mag) {
            String sys = product.substring(4);
            String[] axes = "rtn".equals(sys) ? new String[]{"R", "T", "N"} : new String[]{"x", "y", "z"};
            wanted.put("magnitude", new Object[]{"magnitude", 0});
            for (int i = 0; i < 3; i++)
                wanted.put("b_" + sys + '_' + axes[i], new Object[]{"b_" + sys, i});
        } else {
            String p = "swapi-proton".equals(product) ? "proton_sw" : "alpha_sw";
            for (String s : new String[]{"speed", "speed_sun", "density", "temperature"})
                wanted.put(p + '_' + s, new Object[]{p + '_' + s, 0});
            for (String s : new String[]{"velocity_rtn", "velocity_rtn_sun"})
                for (int i = 0; i < 3; i++)
                    wanted.put(p + '_' + s + '_' + "RTN".charAt(i), new Object[]{p + '_' + s, i});
        }

        long dayStart = firstDay(epoch);
        Map<String, float[]> minutes = new LinkedHashMap<>();
        Map<String, String> units = new LinkedHashMap<>();
        Map<String, float[][]> cache = new LinkedHashMap<>();
        for (Map.Entry<String, Object[]> w : wanted.entrySet()) {
            String varName = (String) w.getValue()[0];
            int axis = (Integer) w.getValue()[1];
            Variable v = vars.get(varName);
            if (v == null)
                continue;
            float[][] data = cache.get(varName);
            if (data == null) {
                data = BandReaderCdf.readCDFVariableFloat(v, fillValue(cdf, v));
                cache.put(varName, data);
            }
            if (axis >= data.length || data[axis].length != epoch.length)
                throw new IOException("Inconsistent shape of " + varName + ": " + uri);
            minutes.put(w.getKey(), toMinutes(epoch, data[axis], flags, dayStart));
            String u = attribute(cdf, v, "UNITS");
            if (u != null)
                units.put(w.getKey(), u);
        }
        return new DayMinutes(product, dayStart, minutes, units, globalAttribute(cdf, "Acknowledgement"));
    }

    private static float[] toMinutes(long[] epoch, float[] values, float[] flags, long dayStart) {
        double[] sum = new double[MINUTES_PER_DAY];
        int[] count = new int[MINUTES_PER_DAY];
        for (int i = 0; i < epoch.length; i++) {
            if (epoch[i] == Long.MIN_VALUE || values[i] == YAxis.BLANK)
                continue;
            if (flags != null && flags[i] != 0) // nonzero or fill (BLANK) flag: drop the record
                continue;
            long idx = Math.floorDiv(epoch[i] - dayStart, MINUTE_MS);
            if (idx < 0 || idx >= MINUTES_PER_DAY)
                continue;
            sum[(int) idx] += values[i];
            count[(int) idx]++;
        }
        float[] ret = new float[MINUTES_PER_DAY];
        for (int i = 0; i < MINUTES_PER_DAY; i++)
            ret[i] = count[i] == 0 ? YAxis.BLANK : (float) (sum[i] / count[i]);
        return ret;
    }

    /** Epoch of every record as Unix ms; fill (Long.MIN_VALUE) stays Long.MIN_VALUE. */
    private static long[] readEpoch(Variable v) throws IOException {
        int n = v.getRecordCount();
        long[] ret = new long[n];
        Object abuf = v.createRawValueArray();
        for (int i = 0; i < n; i++) {
            v.readRawRecord(i, abuf);
            long ns = ((Number) v.getDataType().getScalar(abuf, 0)).longValue();
            ret[i] = ns == Long.MIN_VALUE ? Long.MIN_VALUE : tt2000ToMs(ns);
        }
        return ret;
    }

    private static long firstDay(long[] epoch) throws IOException {
        for (long e : epoch)
            if (e != Long.MIN_VALUE)
                return Math.floorDiv(e, DAY_MS) * DAY_MS;
        throw new IOException("No valid epoch in file");
    }

    private static float fillValue(CdfContent cdf, Variable v) {
        try {
            String f = attribute(cdf, v, "FILLVAL");
            return f == null ? Float.NaN : Float.parseFloat(f);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static String logicalSource(CdfContent cdf) {
        String s = globalAttribute(cdf, "Logical_source");
        return s.isEmpty() ? null : s;
    }

    private static String globalAttribute(CdfContent cdf, String name) {
        for (GlobalAttribute a : cdf.getGlobalAttributes()) {
            if (name.equals(a.getName())) {
                AttributeEntry[] entries = a.getEntries();
                if (entries.length > 0)
                    return entries[0].toString();
            }
        }
        return "";
    }

    private static String attribute(CdfContent cdf, Variable v, String name) {
        for (VariableAttribute a : cdf.getVariableAttributes()) {
            if (name.equals(a.getName())) {
                AttributeEntry e = a.getEntry(v);
                return e == null ? null : e.toString();
            }
        }
        return null;
    }

}
