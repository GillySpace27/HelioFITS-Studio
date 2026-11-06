package org.helioviewer.jhv.movie;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

/**
 * The plain text report of a processing record (hfrecord): a port of {@code text()} in
 * pyHelioFITS's {@code heliofits/core/report.py}, whose layout is the contract. Studio writes it
 * as the sidecar's top-level {@code report} (one line per element, the short file list) and shows
 * it in File &gt; Open Export Record. ExportReportCheck holds it to the Python output line for line.
 *
 * <p>The record is read with {@link #parse}, which keeps key order and number spelling the way
 * Python's json module does, because the report lists parameters in file order and prints
 * numbers as Python would.
 */
public final class ExportReport {

    private static final int WIDTH = 100;
    private static final Set<String> PARAM_NOISE = Set.of("None", "null", "");
    /** The step ops the schema knows (docs/spec/hfrecord-1.schema.json, $defs.step.op). */
    private static final Set<String> KNOWN_OPS = Set.of("instrument_correction", "select_plane", "clip", "stretch",
            "image_filter", "sequence_filter", "levels", "radial_gain", "difference", "sharpen", "rhef_upsilon",
            "colormap", "channel_mask", "geometric_mask", "pointing", "opacity_blend", "radial_transform",
            "reproject", "feather");

    private ExportReport() {}

    /** The lines a writer stores as the record's top-level report (short file list). */
    public static List<String> block(Map<String, Object> record) {
        String t = text(record, false);
        int end = t.length();
        while (end > 0 && t.charAt(end - 1) == '\n')
            end--;
        return List.of(t.substring(0, end).split("\n", -1));
    }

    /** The plain text report: one block for the file, one for the scene, one per layer. */
    public static String text(Map<String, Object> record, boolean full) {
        Map<String, Object> producer = map(record.get("producer"));
        List<Map<String, Object>> rawLayers = maps(record.get("layers"));
        List<String> out = new ArrayList<>(List.of("HELIOFITS SUITE PROCESSING RECORD", ""));
        String head = joinTruthy(" ", producer.get("program"), producer.get("version"));
        List<String> build = new ArrayList<>();
        if (truthy(producer.get("build_id")))
            build.add("build " + str(producer.get("build_id")));
        if (truthy(producer.get("commit")))
            build.add("commit " + str(producer.get("commit")));
        String made = head + (build.isEmpty() ? "" : " (" + String.join(", ", build) + ")");
        out.addAll(wrap("Made by:", made.isEmpty() ? "unknown program" : made, 0));
        String created = s(record.get("created"));
        if (truthy(created))
            out.addAll(wrap("Created:", created, 0));
        boolean dirty = truthy(producer.getOrDefault("dirty", false));
        if (dirty)
            out.addAll(wrap("Warning:", "made by a build with uncommitted changes", 0));
        Map<String, Object> output = map(record.get("output"));
        if (!output.isEmpty()) {
            List<Object> bits = new ArrayList<>();
            bits.add(output.get("kind"));
            bits.add(output.get("file_name"));
            bits.add(output.get("media_type"));
            bits.add(truthy(output.get("width")) && truthy(output.get("height"))
                    ? str(output.get("width")) + " x " + str(output.get("height")) + " px" : null);
            bits.add(truthy(output.get("bit_depth")) ? str(output.get("bit_depth")) + "-bit" : null);
            bits.add(output.get("colorspace"));
            bits.add(truthy(output.get("display_referred")) ? "display-referred (pixel values are colours)" : null);
            out.addAll(wrap("Output:", joinTruthy(", ", bits.toArray()), 0));
            if (truthy(output.get("sha256")))
                out.addAll(wrap("SHA-256:", str(output.get("sha256")), 0));
        }
        Map<String, Object> scene = map(record.get("scene"));
        if (!scene.isEmpty())
            out.addAll(wrap("Scene:", kv(pairs(scene)), 0));

        out.add("");
        out.add("LAYERS (" + rawLayers.size() + ", top layer first)");
        out.add("");
        for (int pos = 1, drawn = rawLayers.size() - 1; drawn >= 0; pos++, drawn--) {
            Map<String, Object> layer = rawLayers.get(drawn);
            Map<String, Object> ds = map(layer.get("dataset"));
            List<Map<String, Object>> sources = maps(layer.get("sources"));
            List<Map<String, Object>> steps = maps(layer.get("steps"));
            String name = s(layer.get("name"));
            String label = s(truthy(ds.get("label")) ? ds.get("label") : layer.get("name"));
            String title = truthy(label) ? label : truthy(name) ? name : "unnamed layer";
            out.add(pos + ". " + title + (Boolean.FALSE.equals(layer.get("enabled")) ? "  [hidden]" : ""));
            String dline = datasetLine(ds);
            if (!dline.isEmpty())
                out.addAll(wrap("Dataset:", dline, 4));
            String key = s(ds.get("dataset_key"));
            if (truthy(key))
                out.addAll(wrap("Key:", key, 4));
            out.addAll(wrap("Data:", dataLine(layer, sources), 4));
            out.addAll(files(sources, layer, full));
            if (!steps.isEmpty()) {
                out.add("    Processing, in order applied:");
                for (int i = 0; i < steps.size(); i++)
                    out.addAll(stepLines(i + 1, steps.get(i)));
            }
            for (Map<String, Object> step : steps)
                if (step.get("caveat") instanceof String c)
                    out.addAll(wrap("Caveat:", c, 4));
            if (layer.get("acknowledgement") instanceof String ack && !ack.isEmpty())
                out.addAll(wrap("Credit:", ack, 4));
            out.add("");
        }
        List<String> caveats = strings(record.get("caveats"));
        if (!caveats.isEmpty()) {
            out.add("CAVEATS");
            for (String c : caveats)
                out.addAll(wrap("-", c, 2));
            out.add("");
        }
        List<String> citations = strings(record.get("citations"));
        if (!citations.isEmpty()) {
            out.add("PLEASE CITE");
            for (String c : citations)
                out.addAll(wrap("-", c, 2));
            out.add("");
        }
        List<String> rebuild = new ArrayList<>();
        if (truthy(producer.get("dirty")))
            rebuild.add("made by a build with uncommitted changes");
        for (Map<String, Object> layer : rawLayers)
            if ("helioviewer_api".equals(map(layer.get("source_spec")).get("kind"))) {
                rebuild.add("a Helioviewer layer cannot promise the same pixels");
                break;
            }
        if (!rebuild.isEmpty())
            out.addAll(wrap("Rebuild:", String.join("; ", rebuild), 0));
        String recordId = s(record.get("record_id"));
        if (truthy(recordId))
            out.addAll(wrap("Record:", recordId + " (hfrecord " + str(s(record.get("hfrecord"))) + ")", 0));
        return rstrip(String.join("\n", out)) + "\n";
    }

    private static String datasetLine(Map<String, Object> ds) {
        String head = joinTruthy(" ", ds.get("observatory"), ds.get("instrument"), ds.get("detector"));
        List<String> parts = new ArrayList<>();
        if (!head.isEmpty())
            parts.add(head);
        if (truthy(ds.get("product")))
            parts.add("product " + str(ds.get("product")));
        if (truthy(ds.get("level")))
            parts.add("level " + str(ds.get("level")));
        Object w = ds.get("wavelength");
        if (isNumber(w)) {
            double v = toDouble(w);
            parts.add(formatG(v) + (v < 100 || "nm".equals(ds.get("wavelength_unit")) ? " nm" : " Å"));
        }
        return String.join(", ", parts);
    }

    private static String dataLine(Map<String, Object> layer, List<Map<String, Object>> sources) {
        long n = frames(layer);
        List<String> bits = new ArrayList<>();
        bits.add(n + " frame" + (n != 1 ? "s" : ""));
        List<String> times = new ArrayList<>();
        Set<String> versions = new TreeSet<>();
        long total = 0;
        for (Map<String, Object> src : sources) {
            if (truthy(src.get("date_obs")))
                times.add(str(src.get("date_obs")));
            if (truthy(src.get("file_version")))
                versions.add(str(src.get("file_version")));
            Object size = src.get("size");
            if (isInt(size))
                total += size instanceof Boolean b ? (b ? 1 : 0) : ((Number) size).longValue();
        }
        Collections.sort(times);
        if (!times.isEmpty())
            bits.add(times.getFirst().equals(times.getLast()) ? times.getFirst() : times.getFirst() + " to " + times.getLast());
        if (!versions.isEmpty())
            bits.add("file version " + String.join(", ", versions));
        if (total != 0)
            bits.add(size(total));
        if (layer.containsKey("source_count"))
            bits.add("source list shortened in the record");
        String spec = s(map(layer.get("source_spec")).get("kind"));
        if (truthy(spec))
            bits.add("source: " + spec);
        return String.join("; ", bits);
    }

    private static long frames(Map<String, Object> layer) {
        Object count = layer.get("source_count");
        if (isInt(count) && !(count instanceof Boolean))
            return ((Number) count).longValue();
        return layer.get("sources") instanceof List<?> l ? l.size() : 0;
    }

    private static String fileName(Map<String, Object> src) {
        if (truthy(src.get("original_name")))
            return str(src.get("original_name"));
        if (truthy(src.get("source_url")))
            return str(src.get("source_url"));
        Object sha = src.get("sha256");
        return truthy(sha) ? prefix(str(sha), 12) : "";
    }

    private static List<String> files(List<Map<String, Object>> sources, Map<String, Object> layer, boolean full) {
        List<String> lines = new ArrayList<>();
        if (sources.isEmpty())
            return lines;
        if (full || sources.size() <= 3) {
            lines.add("    Files:");
            for (Map<String, Object> src : sources)
                lines.add("      " + fileName(src) + (truthy(src.get("sha256")) ? "  sha256 " + prefix(str(src.get("sha256")), 12) : ""));
            return lines;
        }
        long missing = sources.stream().filter(src -> Boolean.FALSE.equals(src.get("in_library"))).count();
        lines.addAll(wrap("Files:", sources.size() + " files, first " + fileName(sources.getFirst()) + ", last " + fileName(sources.getLast())
                + (missing != 0 ? "; " + missing + " not found in the library" : "")
                + " (hfcore record show --full lists every file)", 4));
        if (truthy(layer.get("source_set_sha256")))
            lines.addAll(wrap("Set hash:", "sha256 " + str(layer.get("source_set_sha256")), 4));
        return lines;
    }

    private static List<String> stepLines(int index, Map<String, Object> step) {
        String op = str(step.getOrDefault("op", ""));
        StringBuilder head = new StringBuilder("      " + index + ". " + op);
        String stage = s(step.get("stage"));
        String algorithm = s(step.get("algorithm"));
        String implementation = s(step.get("implementation"));
        if (truthy(stage))
            head.append(" (").append(stage).append(')');
        if (truthy(algorithm))
            head.append(": ").append(algorithm);
        if (truthy(implementation))
            head.append(" via ").append(implementation);
        if (truthy(step.getOrDefault("automatic", false)))
            head.append(" [automatic]");
        if (!KNOWN_OPS.contains(op))
            head.append(" [step not known to this reader]");
        List<String> lines = new ArrayList<>(List.of(head.toString()));
        for (String label : List.of("params", "resolved", "units")) {
            String body = kv(pairs(step.get(label)));
            if (!body.isEmpty())
                lines.addAll(wrap((label.equals("params") ? "parameters" : label) + ":", body, 10));
        }
        String reference = reference(step.get("reference"));
        if (truthy(reference))
            lines.addAll(wrap("reference:", reference, 10));
        if (step.get("varies") instanceof Map<?, ?> varies) {
            List<String> names = new ArrayList<>();
            if (varies.get("params") instanceof List<?> l)
                for (Object p : l)
                    names.add(str(p));
            lines.addAll(wrap("varies:", names.isEmpty() ? "over the movie" : String.join(", ", names), 10));
        }
        for (String key : List.of("caveat", "citation")) {
            String v = s(step.get(key));
            if (truthy(v))
                lines.addAll(wrap(key + ":", v, 10));
        }
        return lines;
    }

    @Nullable
    private static String reference(Object value) {
        if (!(value instanceof Map<?, ?> ref))
            return null;
        Object kind = ref.get("kind");
        if ("running".equals(kind))
            return "previous frame (running difference)";
        if ("base".equals(kind))
            return "base frame: source " + str(ref.get("source_index"));
        if ("compare".equals(kind))
            return "compared with layer " + str(ref.get("layer_id"));
        return text(ref);
    }

    // ---- Python's text helpers ----------------------------------------------------------------

    /** label then text on the first line; continuation lines hang under the text (report.py _wrap). */
    static List<String> wrap(String label, String text, int indent) {
        String first = " ".repeat(indent) + label;
        int start = Math.max(len(first) + 1, indent + 11);
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder(first).append(" ".repeat(Math.max(0, start - len(first))));
        for (String w : split(text)) {
            if (len(cur) + len(w) + 1 > WIDTH && !strip(cur.toString()).isEmpty()) {
                lines.add(rstrip(cur.toString()));
                cur = new StringBuilder(" ".repeat(start));
            }
            if (cur.isEmpty() || cur.charAt(cur.length() - 1) != ' ')
                cur.append(' ');
            cur.append(w);
        }
        lines.add(rstrip(cur.toString()));
        return lines;
    }

    private static String kv(List<String[]> pairs) {
        List<String> out = new ArrayList<>();
        for (String[] p : pairs)
            if (!PARAM_NOISE.contains(p[1]))
                out.add(p[0] + "=" + p[1]);
        return String.join(", ", out);
    }

    /** _pairs: a dict's items as name and _text(value), in file order; anything else none. */
    private static List<String[]> pairs(Object obj) {
        List<String[]> out = new ArrayList<>();
        if (obj instanceof Map<?, ?> m)
            for (Map.Entry<?, ?> e : m.entrySet())
                out.add(new String[]{String.valueOf(e.getKey()), text(e.getValue())});
        return out;
    }

    /** _s: a string field as it is, null as null, anything else as JSON text. */
    @Nullable
    private static String s(@Nullable Object value) {
        return value == null ? null : text(value);
    }

    /** _text: a string as it is, anything else as json.dumps(value, ensure_ascii=False, separators=(", ", ": ")). */
    static String text(@Nullable Object value) {
        return value instanceof String str ? str : dumps(value);
    }

    static String dumps(@Nullable Object v) {
        if (v == null)
            return "null";
        if (v instanceof Boolean b)
            return b ? "true" : "false";
        if (v instanceof Double d)
            return Double.isNaN(d) ? "NaN" : Double.isInfinite(d) ? (d > 0 ? "Infinity" : "-Infinity") : floatRepr(d);
        if (v instanceof Number n)
            return n.toString();
        if (v instanceof String str)
            return quote(str);
        StringBuilder sb = new StringBuilder();
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            String sep = "";
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(sep).append(quote(String.valueOf(e.getKey()))).append(": ").append(dumps(e.getValue()));
                sep = ", ";
            }
            return sb.append('}').toString();
        }
        if (v instanceof List<?> l) {
            sb.append('[');
            String sep = "";
            for (Object o : l) {
                sb.append(sep).append(dumps(o));
                sep = ", ";
            }
            return sb.append(']').toString();
        }
        return quote(v.toString());
    }

    private static String quote(String str) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Python's str(): None, True and False by their Python names, floats as repr, containers as JSON (close enough to repr for a report). */
    private static String str(@Nullable Object v) {
        if (v == null)
            return "None";
        if (v instanceof Boolean b)
            return b ? "True" : "False";
        if (v instanceof Double d)
            return Double.isNaN(d) ? "nan" : Double.isInfinite(d) ? (d > 0 ? "inf" : "-inf") : floatRepr(d);
        if (v instanceof String || v instanceof Number)
            return v.toString();
        return dumps(v);
    }

    /** Python's repr(float): the shortest digits that round-trip, fixed from 1e-4 up to 1e16, else e notation. */
    static String floatRepr(double d) {
        if (d == 0)
            return 1 / d < 0 ? "-0.0" : "0.0";
        String sign = d < 0 ? "-" : "";
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int exp = digits.length() - 1 - bd.scale();
        if (exp >= -4 && exp < 16) {
            String plain = bd.toPlainString();
            return sign + (plain.contains(".") ? plain : plain + ".0");
        }
        String mantissa = digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
        return sign + mantissa + "e" + (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
    }

    /** Python's format(x, "g"): six significant digits, trailing zeros dropped. */
    static String formatG(double d) {
        if (d == 0)
            return 1 / d < 0 ? "-0" : "0";
        if (Double.isNaN(d))
            return "nan";
        if (Double.isInfinite(d))
            return d > 0 ? "inf" : "-inf";
        BigDecimal r = new BigDecimal(d).round(new java.math.MathContext(6, RoundingMode.HALF_EVEN));
        int exp = r.precision() - r.scale() - 1;
        if (exp >= -4 && exp < 6) {
            BigDecimal fixed = r.setScale(Math.max(0, 5 - exp), RoundingMode.HALF_EVEN).stripTrailingZeros();
            return fixed.scale() < 0 ? fixed.setScale(0).toPlainString() : fixed.toPlainString();
        }
        BigDecimal mant = r.movePointLeft(exp).stripTrailingZeros();
        String m = mant.scale() < 0 ? mant.setScale(0).toPlainString() : mant.toPlainString();
        return m + "e" + (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
    }

    /** report.py _size: bytes in B, kB, MB or GB (powers of 1000), rounded as Python rounds. */
    static String size(long n) {
        if (n < 1000)
            return n + " B";
        double v = n / 1000.0;
        for (String unit : List.of("kB", "MB", "GB")) {
            if (v < 1000 || unit.equals("GB"))
                return new BigDecimal(v).setScale(1, RoundingMode.HALF_EVEN).toPlainString() + " " + unit;
            v /= 1000;
        }
        return "";
    }

    private static boolean isInt(@Nullable Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof BigInteger || v instanceof Boolean;
    }

    private static boolean isNumber(@Nullable Object v) {
        return isInt(v) || v instanceof Double;
    }

    private static double toDouble(Object v) {
        return v instanceof Boolean b ? (b ? 1 : 0) : ((Number) v).doubleValue();
    }

    /** Python truthiness. */
    static boolean truthy(@Nullable Object v) {
        if (v == null)
            return false;
        if (v instanceof Boolean b)
            return b;
        if (v instanceof Double d)
            return d != 0;
        if (v instanceof BigInteger b)
            return b.signum() != 0;
        if (v instanceof Number n)
            return n.longValue() != 0;
        if (v instanceof String str)
            return !str.isEmpty();
        if (v instanceof Map<?, ?> m)
            return !m.isEmpty();
        if (v instanceof List<?> l)
            return !l.isEmpty();
        return true;
    }

    private static String joinTruthy(String sep, Object... values) {
        List<String> out = new ArrayList<>();
        for (Object v : values)
            if (truthy(v))
                out.add(str(v));
        return String.join(sep, out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(@Nullable Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(@Nullable Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l)
            for (Object o : l)
                if (o instanceof Map<?, ?> m)
                    out.add((Map<String, Object>) m);
        return out;
    }

    private static List<String> strings(@Nullable Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l)
            for (Object o : l)
                if (o instanceof String str)
                    out.add(str);
        return out;
    }

    // Python counts code points and calls a wider set of characters whitespace than Java does.

    private static int len(CharSequence cs) {
        return Character.codePointCount(cs, 0, cs.length());
    }

    private static String prefix(String str, int n) {
        return len(str) <= n ? str : str.substring(0, str.offsetByCodePoints(0, n));
    }

    private static boolean space(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85;
    }

    private static List<String> split(String str) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < str.length()) {
            while (i < str.length() && space(str.codePointAt(i)))
                i += Character.charCount(str.codePointAt(i));
            int start = i;
            while (i < str.length() && !space(str.codePointAt(i)))
                i += Character.charCount(str.codePointAt(i));
            if (i > start)
                out.add(str.substring(start, i));
        }
        return out;
    }

    private static String rstrip(String str) {
        int end = str.length();
        while (end > 0 && space(str.codePointBefore(end)))
            end -= Character.charCount(str.codePointBefore(end));
        return str.substring(0, end);
    }

    private static String strip(String str) {
        String r = rstrip(str);
        int i = 0;
        while (i < r.length() && space(r.codePointAt(i)))
            i += Character.charCount(r.codePointAt(i));
        return r.substring(i);
    }

    // ---- reading ------------------------------------------------------------------------------

    /**
     * JSON as Python's json module reads it: objects as insertion-ordered maps, integers as Long
     * (BigInteger when larger), other numbers as Double, null as null.
     */
    public static Map<String, Object> parse(String json) {
        Parser p = new Parser(json);
        Object v = p.value();
        p.ws();
        if (p.i != json.length())
            throw p.error("extra data");
        if (!(v instanceof Map<?, ?>))
            throw p.error("not a JSON object");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("JSON: " + what + " at character " + i);
        }

        void ws() {
            while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0)
                i++;
        }

        boolean take(String word) {
            if (s.startsWith(word, i)) {
                i += word.length();
                return true;
            }
            return false;
        }

        @Nullable
        Object value() {
            ws();
            if (i >= s.length())
                throw error("unexpected end");
            char c = s.charAt(i);
            if (c == '{')
                return object();
            if (c == '[')
                return array();
            if (c == '"')
                return string();
            if (take("true"))
                return Boolean.TRUE;
            if (take("false"))
                return Boolean.FALSE;
            if (take("null"))
                return null;
            if (take("NaN"))
                return Double.NaN;
            if (take("Infinity"))
                return Double.POSITIVE_INFINITY;
            if (take("-Infinity"))
                return Double.NEGATIVE_INFINITY;
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (take("}"))
                return m;
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"')
                    throw error("expected a key");
                String key = string();
                ws();
                if (!take(":"))
                    throw error("expected ':'");
                m.put(key, value());
                ws();
                if (take("}"))
                    return m;
                if (!take(","))
                    throw error("expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (take("]"))
                return l;
            while (true) {
                l.add(value());
                ws();
                if (take("]"))
                    return l;
                if (!take(","))
                    throw error("expected ',' or ']'");
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length())
                    throw error("unterminated string");
                char c = s.charAt(i++);
                if (c == '"')
                    return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length())
                    throw error("unterminated string");
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length())
                            throw error("bad \\u escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad \\u escape");
                        }
                        i += 4;
                    }
                    case '"', '\\', '/' -> sb.append(e);
                    default -> throw error("bad escape");
                }
            }
        }

        Object number() {
            int start = i;
            boolean real = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '.' || c == 'e' || c == 'E')
                    real = true;
                else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9')))
                    break;
                i++;
            }
            String t = s.substring(start, i);
            if (t.isEmpty())
                throw error("unexpected character");
            try {
                if (real)
                    return Double.parseDouble(t);
                BigInteger b = new BigInteger(t);
                return b.bitLength() < 64 ? (Object) b.longValue() : b;
            } catch (NumberFormatException ex) {
                throw error("bad number " + t);
            }
        }
    }

}
