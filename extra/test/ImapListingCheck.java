package org.helioviewer.jhv.timelines.band;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.NavigableMap;

/**
 * SPDF Apache directory listings: the highest (version, suffix) wins per day, names of other products, i-ALiRT,
 * temporary files and impossible dates are ignored, mtime and size are read, and the persisted form round trips.
 * extra/test/data/imap-spdf-listing.html is hand-built in the real listing's row format (see make_imap_fixtures.py).
 *
 * <p>Run from the repository root: java -cp extra/test-classes:bin org.helioviewer.jhv.timelines.band.ImapListingCheck
 */
public final class ImapListingCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static String row(String name, String when, String size) {
        return "<tr><td><a href=\"" + name + "\">" + name + "</a></td><td align=\"right\">" + when
                + "  </td><td align=\"right\">" + size + "</td></tr>\n";
    }

    public static void main(String[] args) throws Exception {
        Path f = Path.of("extra/test/data/imap-spdf-listing.html");
        if (!Files.exists(f)) {
            System.out.println("  FAIL fixture missing: " + f + " (run from the repository root)");
            System.exit(1);
        }
        String html = Files.readString(f);
        NavigableMap<LocalDate, ImapListing.Entry> m = ImapListing.parse(html, ImapListing.pattern("swapi", "l3a", "proton-sw"));

        expect("three proton days survive (got " + m.keySet() + ")", m.size() == 3);
        expect("2026-01-01 is the only v002.0011", m.get(LocalDate.of(2026, 1, 1)) != null
                && "imap_swapi_l3a_proton-sw_20260101_v002.0011.cdf".equals(m.get(LocalDate.of(2026, 1, 1)).name()));
        expect("2026-01-02: v002.0012 beats v002.0011 and v001",
                "imap_swapi_l3a_proton-sw_20260102_v002.0012.cdf".equals(m.get(LocalDate.of(2026, 1, 2)).name()));
        expect("2026-01-05: v002.0011 beats v002 with no suffix",
                "imap_swapi_l3a_proton-sw_20260105_v002.0011.cdf".equals(m.get(LocalDate.of(2026, 1, 5)).name()));
        expect("a day absent from the listing is absent (2026-01-03)", m.get(LocalDate.of(2026, 1, 3)) == null);
        expect("the .tmp file, the 13th-month date, the i-ALiRT name and the alpha file are ignored",
                m.get(LocalDate.of(2026, 3, 1)) == null && m.size() == 3);
        ImapListing.Entry e = m.get(LocalDate.of(2026, 1, 1));
        expect("mtime read as UTC (" + e.mtimeMs() + ")", e.mtimeMs() == Instant.parse("2026-11-05T10:48:00Z").toEpochMilli());
        expect("size 174K read as 174 KiB", e.bytes() == 174L * 1024);

        NavigableMap<LocalDate, ImapListing.Entry> alpha = ImapListing.parse(html, ImapListing.pattern("swapi", "l3a", "alpha-sw"));
        expect("the alpha pattern sees only the alpha file", alpha.size() == 1 && alpha.containsKey(LocalDate.of(2026, 1, 1)));
        expect("a pattern for another product matches nothing",
                ImapListing.parse(html, ImapListing.pattern("mag", "l2", "norm-rtn")).isEmpty());

        // a newer version without suffix beats an older one with a high suffix; numeric, not text, comparison
        String h2 = row("imap_mag_l2_norm-rtn_20260110_v002.0099.cdf", "2026-11-05 10:48", "3.6M")
                + row("imap_mag_l2_norm-rtn_20260110_v003.cdf", "2026-11-09 10:48", "3.7M")
                + row("imap_mag_l2_norm-rtn_20260111_v010.cdf", "2026-11-09 10:48", "3.7M")
                + row("imap_mag_l2_norm-rtn_20260111_v009.0001.cdf", "2026-11-09 10:48", "3.7M");
        NavigableMap<LocalDate, ImapListing.Entry> r = ImapListing.parse(h2, ImapListing.pattern("mag", "l2", "norm-rtn"));
        expect("v003 beats v002.0099", "imap_mag_l2_norm-rtn_20260110_v003.cdf".equals(r.get(LocalDate.of(2026, 1, 10)).name()));
        expect("v010 beats v009.0001", "imap_mag_l2_norm-rtn_20260111_v010.cdf".equals(r.get(LocalDate.of(2026, 1, 11)).name()));
        expect("3.7M read as about 3.7 MiB", Math.abs(r.get(LocalDate.of(2026, 1, 11)).bytes() - 3.7 * 1048576) < 1);

        expect("an empty page gives an empty listing", ImapListing.parse("", ImapListing.pattern("mag", "l2", "norm-rtn")).isEmpty());
        expect("a page that is not a listing gives an empty listing",
                ImapListing.parse("<html>404 Not Found</html>", ImapListing.pattern("mag", "l2", "norm-rtn")).isEmpty());

        NavigableMap<LocalDate, ImapListing.Entry> back = ImapListing.deserialize(ImapListing.serialize(m));
        expect("the persisted form round trips", back.equals(m));
        expect("a damaged persisted line is skipped, the rest kept",
                ImapListing.deserialize("garbage\n2026-01-01\tname.cdf\t1\t2\n2026-13-01\tx\t1\t2\n").size() == 1);

        System.out.println(failures == 0 ? "ImapListingCheck: all ok" : "ImapListingCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

}
