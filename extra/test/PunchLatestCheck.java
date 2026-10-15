package org.helioviewer.jhv.io;

/** PunchLatest's two pure rules: how an age reads, and when it turns amber (older than a week). */
public final class PunchLatestCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        long newest = java.time.Instant.parse("2026-08-17T12:30:00Z").toEpochMilli();
        long day = 86_400_000L;
        expect("an old age reads in days and hours", PunchLatest.ageText(newest, newest + 59 * day + 3 * 3_600_000L)
                .equals("newest 2026-08-17 12:30 UTC, 59 d 3 h old"));
        expect("under a day reads in hours", PunchLatest.ageText(newest, newest + 5 * 3_600_000L).endsWith("5 h old"));
        expect("a frame from the future is age zero, not negative", PunchLatest.ageText(newest, newest - day).endsWith("0 h old"));
        expect("seven days is not stale", !PunchLatest.stale(newest, newest + 7 * day));
        expect("seven days and a millisecond is", PunchLatest.stale(newest, newest + 7 * day + 1));
        if (failures > 0) {
            System.out.println("PunchLatestCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("PunchLatestCheck: all passed");
    }
}
