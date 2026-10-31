package org.helioviewer.jhv.study;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File and folder names from a pattern. Fields: {scene} {variant} {time} {projection} {pizza}
 * {fov} {zoom} {lambda} {index} {date}. A field that has no value is left as written, so a typo
 * shows in the file name instead of silently vanishing.
 */
public final class NamePattern {

    private static final Pattern FIELD = Pattern.compile("\\{([a-zA-Z]+)}");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private NamePattern() {
    }

    /** {time} as the pattern wants it: UTC, minute resolution. */
    public static String time(long milli) {
        return TIME.format(java.time.Instant.ofEpochMilli(milli));
    }

    public static String date(long milli) {
        return DATE.format(java.time.Instant.ofEpochMilli(milli));
    }

    /** {index}: 1-based row number zero-padded to the width of the row count. */
    public static String index(int index, int count) {
        return String.format("%0" + Math.max(1, Integer.toString(count).length()) + "d", index);
    }

    public static String expand(String pattern, Map<String, String> fields) {
        return expand(pattern, fields, null);
    }

    /**
     * @param swept the field a sweep varies, or null; when the pattern does not use it, "_field-value"
     *              is appended so the names of a sweep stay unique.
     */
    public static String expand(String pattern, Map<String, String> fields, String swept) {
        String p = pattern;
        if (swept != null && fields.containsKey(swept) && !p.contains("{" + swept + "}"))
            p += "_" + swept + "-{" + swept + "}";
        Matcher m = FIELD.matcher(p);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = fields.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v.replaceAll("[/\\\\:]", "_")));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
