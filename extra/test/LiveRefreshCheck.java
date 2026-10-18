package org.helioviewer.jhv.io;

/** LiveRefresh's pure rules: the archive age gate, the back-off, and one request in flight. */
public final class LiveRefreshCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        long now = java.time.Instant.parse("2026-10-15T00:00:00Z").toEpochMilli();
        long day = 86_400_000L;
        expect("a newest day of six days ago opens the gate", LiveRefresh.gateOpen(now - 6 * day, now));
        expect("exactly seven days still opens it", LiveRefresh.gateOpen(now - 7 * day, now));
        expect("the 2026-08-17 reading keeps it closed", !LiveRefresh.gateOpen(java.time.Instant.parse("2026-08-17T00:00:00Z").toEpochMilli(), now));
        expect("an unknown archive day keeps it closed", !LiveRefresh.gateOpen(0, now));
        expect("no failures waits one interval", LiveRefresh.nextDelay(0) == LiveRefresh.INTERVAL_MILLIS);
        expect("a failure doubles it", LiveRefresh.nextDelay(1) == 2 * LiveRefresh.INTERVAL_MILLIS);
        expect("back-off is capped", LiveRefresh.nextDelay(50) == LiveRefresh.MAX_BACKOFF_MILLIS);
        LiveRefresh.InFlight f = new LiveRefresh.InFlight();
        expect("the first request begins", f.tryBegin(0));
        expect("a second waits", !f.tryBegin(1000));
        expect("a request that never reports is given up after two intervals", f.tryBegin(2 * LiveRefresh.INTERVAL_MILLIS));
        f.end();
        expect("after end a new one begins", f.tryBegin(2 * LiveRefresh.INTERVAL_MILLIS + 1));
        if (failures > 0) {
            System.out.println("LiveRefreshCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("LiveRefreshCheck: all passed");
    }
}
