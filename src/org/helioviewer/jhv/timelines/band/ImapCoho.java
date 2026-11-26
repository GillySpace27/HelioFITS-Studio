package org.helioviewer.jhv.timelines.band;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.timelines.draw.YAxis;

/**
 * Parser for the SPDF merged IMAP hourly magnetic field and proton plasma text files (COHO format, one file per
 * year, 16 words per line, see aareadme_imap_coho). Row h holds the mean over [h, h+1), so the time tag is the
 * HOUR START. Fill values are per column; they become YAxis.BLANK. Lines that do not have 16 numeric words
 * (headers, the daily file's 15 words, blanks) are counted in skippedLines and ignored.
 */
final class ImapCoho {

    /** Names of the data columns (words 4 to 16), index into Columns.values. */
    static final String[] NAMES = {"R_AU", "HGI_lat", "HGI_lon", "B_R", "B_T", "B_N", "B_mag", "V", "V_R", "V_T",
            "V_N", "N_p", "T_p"};
    static final String[] UNITS = {"AU", "deg", "deg", "nT", "nT", "nT", "nT", "km/s", "km/s", "km/s", "km/s",
            "cm^-3", "K"};
    private static final float[] FILL = {999.99f, 9999.9f, 9999.9f, 9999.99f, 9999.99f, 9999.99f, 9999.99f,
            99999.9f, 99999.9f, 99999.9f, 99999.9f, 999.99f, 9999999f};
    private static final int WORDS = 3 + NAMES.length;

    /** hourStartMs: UTC ms of each row's hour start; values[col][row] indexed like NAMES. */
    record Columns(long[] hourStartMs, float[][] values, int skippedLines) {}

    private ImapCoho() {}

    static int column(String name) {
        for (int i = 0; i < NAMES.length; i++)
            if (NAMES[i].equals(name))
                return i;
        return -1;
    }

    static Columns parse(InputStream in) throws IOException {
        List<long[]> times = new ArrayList<>();
        List<float[]> rows = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank())
                    continue;
                String[] w = line.trim().split("\\s+");
                if (w.length != WORDS || !parseLine(w, times, rows))
                    skipped++;
            }
        }
        int n = rows.size();
        long[] hours = new long[n];
        float[][] values = new float[NAMES.length][n];
        for (int i = 0; i < n; i++) {
            hours[i] = times.get(i)[0];
            float[] row = rows.get(i);
            for (int c = 0; c < NAMES.length; c++)
                values[c][i] = row[c];
        }
        return new Columns(hours, values, skipped);
    }

    private static boolean parseLine(String[] w, List<long[]> times, List<float[]> rows) {
        try {
            int year = Integer.parseInt(w[0]);
            int doy = Integer.parseInt(w[1]);
            int hour = Integer.parseInt(w[2]);
            if (hour < 0 || hour > 23)
                return false;
            float[] row = new float[NAMES.length];
            for (int c = 0; c < NAMES.length; c++) {
                float v = Float.parseFloat(w[3 + c]);
                row[c] = !Float.isFinite(v) || v == FILL[c] ? YAxis.BLANK : v;
            }
            long ms = LocalDate.ofYearDay(year, doy).toEpochDay() * 86_400_000L + hour * 3_600_000L;
            times.add(new long[]{ms});
            rows.add(row);
            return true;
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return false;
        }
    }

}
