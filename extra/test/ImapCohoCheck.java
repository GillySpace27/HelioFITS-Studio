package org.helioviewer.jhv.timelines.band;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.helioviewer.jhv.timelines.draw.YAxis;

/**
 * The merged IMAP hourly text file (SPDF COHO format): hour-start time tags, per-column fill to BLANK, junk lines
 * counted and skipped. The fixture extra/test/data/imap-merged-hr-sample.txt is eight rows of the real
 * imap_merged_hr2026.txt (2026-01-01 hours 0 to 5, 7, 8), one row with fills written like the real file does
 * (hour 6), and one header line that is not data.
 *
 * <p>Run from the repository root: java -cp extra/test-classes:bin org.helioviewer.jhv.timelines.band.ImapCohoCheck
 */
public final class ImapCohoCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static boolean near(float a, double b) {
        return Math.abs(a - b) < 1e-4 * Math.max(1, Math.abs(b));
    }

    private static long ms(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    public static void main(String[] args) throws Exception {
        Path f = Path.of("extra/test/data/imap-merged-hr-sample.txt");
        if (!Files.exists(f)) {
            System.out.println("  FAIL fixture missing: " + f + " (run from the repository root)");
            System.exit(1);
        }
        ImapCoho.Columns c;
        try (InputStream in = Files.newInputStream(f)) {
            c = ImapCoho.parse(in);
        }
        int n = c.hourStartMs().length;
        expect("nine data rows and one skipped header line (rows " + n + ", skipped " + c.skippedLines() + ")",
                n == 9 && c.skippedLines() == 1);
        expect("thirteen data columns", c.values().length == 13 && ImapCoho.NAMES.length == 13);

        // the tag is the HOUR START: row 0 is 2026-01-01 hour 0 and covers [00:00, 01:00)
        expect("row 0 is tagged 2026-01-01T00:00Z", c.hourStartMs()[0] == ms("2026-01-01T00:00:00Z"));
        expect("row 8 is tagged 2026-01-01T08:00Z", c.hourStartMs()[8] == ms("2026-01-01T08:00:00Z"));
        boolean steps = true;
        for (int i = 1; i < n; i++)
            steps &= c.hourStartMs()[i] - c.hourStartMs()[i - 1] == 3_600_000L;
        expect("rows are one hour apart", steps);

        // values of row 0, the first data line of the real file
        expect("B_R row 0 is -0.74", near(c.values()[ImapCoho.column("B_R")][0], -0.74));
        expect("B_mag row 0 is 7.65 (column 10, the mean of |B|)", near(c.values()[ImapCoho.column("B_mag")][0], 7.65));
        expect("V row 0 is 469.2 and V_R 467.2", near(c.values()[ImapCoho.column("V")][0], 469.2)
                && near(c.values()[ImapCoho.column("V_R")][0], 467.2));
        expect("N_p row 0 is 4.52 and T_p 183177", near(c.values()[ImapCoho.column("N_p")][0], 4.52)
                && near(c.values()[ImapCoho.column("T_p")][0], 183177));
        expect("R_AU row 0 is 0.97", near(c.values()[ImapCoho.column("R_AU")][0], 0.97));

        // row 6 carries the fills of B (9999.99), V_N (99999.9) and T (9999999.)
        int[] blank = {ImapCoho.column("B_R"), ImapCoho.column("B_T"), ImapCoho.column("B_N"),
                ImapCoho.column("B_mag"), ImapCoho.column("V_N"), ImapCoho.column("T_p")};
        boolean allBlank = true;
        for (int col : blank)
            allBlank &= c.values()[col][6] == YAxis.BLANK;
        expect("fill values of row 6 become BLANK", allBlank);
        expect("neighbours of the fill are untouched", near(c.values()[ImapCoho.column("B_R")][5], -1.78)
                && near(c.values()[ImapCoho.column("B_R")][7], -2.97));
        expect("a valid column of the fill row survives (V 480.2, N_p 5.94)",
                near(c.values()[ImapCoho.column("V")][6], 480.2) && near(c.values()[ImapCoho.column("N_p")][6], 5.94));

        // day of year, including a leap year, from synthetic lines (not mission data)
        String syn = "2026 212 23 1.00 5.7 232.3 3.29 -2.02 2.17 4.64 305.4 304.8 16.8 -0.9 6.04 53082.\n"
                + "2024 366  0 1.00 5.7 232.3 3.29 -2.02 2.17 4.64 305.4 304.8 16.8 -0.9 6.04 53082.\n"
                + "2026 366  0 1.00 5.7 232.3 3.29 -2.02 2.17 4.64 305.4 304.8 16.8 -0.9 6.04 53082.\n"
                + "2026   5 24 1.00 5.7 232.3 3.29 -2.02 2.17 4.64 305.4 304.8 16.8 -0.9 6.04 53082.\n"
                + "2026   5  3 1.00 5.7 232.3 3.29 -2.02 2.17 4.64 305.4 304.8 16.8 -0.9 6.04\n\n";
        ImapCoho.Columns s = ImapCoho.parse(new ByteArrayInputStream(syn.getBytes(StandardCharsets.US_ASCII)));
        expect("day 212 hour 23 of 2026 is 2026-07-31T23:00Z", s.hourStartMs().length == 2
                && s.hourStartMs()[0] == ms("2026-07-31T23:00:00Z"));
        expect("day 366 of 2024 is 2024-12-31", s.hourStartMs()[1] == ms("2024-12-31T00:00:00Z"));
        expect("day 366 of 2026, hour 24 and a 15-word line are skipped, not parsed (skipped " + s.skippedLines() + ")",
                s.skippedLines() == 3);

        ImapCoho.Columns e = ImapCoho.parse(new ByteArrayInputStream(new byte[0]));
        expect("an empty file gives no rows", e.hourStartMs().length == 0 && e.values()[0].length == 0);

        System.out.println(failures == 0 ? "ImapCohoCheck: all ok" : "ImapCohoCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

}
