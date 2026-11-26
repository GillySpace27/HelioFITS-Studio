package org.helioviewer.jhv.timelines.band;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an Apache directory listing of SPDF and picks, for every UTC day, the file with the highest
 * (version, suffix). Names that do not match the pattern (other products, i-ALiRT, junk) are ignored.
 * A pattern must define named groups date (yyyyMMdd), ver (digits) and optionally suf (digits).
 */
final class ImapListing {

    record Entry(String name, long mtimeMs, long bytes) {}

    private static final Pattern ROW = Pattern.compile(
            "<a href=\"([^\"?/]+)\">[^<]*</a></td>\\s*<td[^>]*>\\s*(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})?\\s*</td>\\s*<td[^>]*>\\s*([^<]*?)\\s*</td>");

    private ImapListing() {}

    /** Pattern for one SPDF product, e.g. pattern("swapi", "l3a", "proton-sw"). */
    static Pattern pattern(String instrument, String level, String descriptor) {
        return Pattern.compile("imap_" + Pattern.quote(instrument) + '_' + Pattern.quote(level) + '_'
                + Pattern.quote(descriptor) + "_(?<date>\\d{8})_v(?<ver>\\d{3})(?:\\.(?<suf>\\d{4}))?\\.cdf");
    }

    static NavigableMap<LocalDate, Entry> parse(String html, Pattern p) {
        TreeMap<LocalDate, Entry> best = new TreeMap<>();
        TreeMap<LocalDate, long[]> bestKey = new TreeMap<>();
        Matcher m = ROW.matcher(html);
        while (m.find()) {
            String name = m.group(1);
            Matcher n = p.matcher(name);
            if (!n.matches())
                continue;
            LocalDate day;
            long ver;
            long suf;
            try {
                String d = n.group("date");
                day = LocalDate.of(Integer.parseInt(d.substring(0, 4)), Integer.parseInt(d.substring(4, 6)),
                        Integer.parseInt(d.substring(6, 8)));
                ver = Long.parseLong(n.group("ver"));
                String s = n.group("suf");
                suf = s == null ? -1 : Long.parseLong(s);
            } catch (NumberFormatException | java.time.DateTimeException e) {
                continue;
            }
            long[] key = {ver, suf};
            long[] old = bestKey.get(day);
            if (old != null && (old[0] > ver || (old[0] == ver && old[1] >= suf)))
                continue;
            bestKey.put(day, key);
            best.put(day, new Entry(name, mtime(m.group(2)), bytes(m.group(3))));
        }
        return best;
    }

    private static long mtime(String s) {
        if (s == null)
            return 0;
        try {
            return LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (java.time.DateTimeException e) {
            return 0;
        }
    }

    /** Apache sizes: "174K", "3.6M", "512", "-" (approximate, rounded by the server). */
    private static long bytes(String s) {
        if (s == null || s.isEmpty() || "-".equals(s))
            return 0;
        try {
            char u = Character.toUpperCase(s.charAt(s.length() - 1));
            long mult = u == 'K' ? 1L << 10 : u == 'M' ? 1L << 20 : u == 'G' ? 1L << 30 : 1;
            String num = mult == 1 ? s : s.substring(0, s.length() - 1);
            return (long) (Double.parseDouble(num) * mult);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** One line per day: date, name, mtime, bytes (tab separated); for the persisted copy of a listing. */
    static String serialize(NavigableMap<LocalDate, Entry> listing) {
        StringBuilder sb = new StringBuilder();
        listing.forEach((d, e) -> sb.append(d).append('\t').append(e.name()).append('\t').append(e.mtimeMs())
                .append('\t').append(e.bytes()).append('\n'));
        return sb.toString();
    }

    /** Inverse of serialize; malformed lines are skipped. */
    static NavigableMap<LocalDate, Entry> deserialize(String text) {
        TreeMap<LocalDate, Entry> ret = new TreeMap<>();
        for (String line : text.split("\n")) {
            String[] f = line.split("\t");
            if (f.length != 4)
                continue;
            try {
                ret.put(LocalDate.parse(f[0]), new Entry(f[1], Long.parseLong(f[2]), Long.parseLong(f[3])));
            } catch (NumberFormatException | java.time.DateTimeException e) {
                // skip
            }
        }
        return ret;
    }

}
