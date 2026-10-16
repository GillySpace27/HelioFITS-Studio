package org.helioviewer.jhv.io;

import java.util.List;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.MapMode;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * PUNCH latest: how old the newest PUNCH frame is, said honestly, and one action that opens the newest
 * day the archive holds in Helioradial with RHEF. It reads only; the archive is the SDAC tree
 * {@link PunchClient} already lists, and a quiet archive shows up as an old age, never as a made-up one.
 * The live refresh loop (HS-12 phase 2) is deliberately not here; see the pull request.
 */
public final class PunchLatest {

    /** Past this the chip turns amber: older than a week is not "latest" in any sense a person means. */
    public static final long STALE_MILLIS = 7 * TimeUtils.DAY_IN_MILLIS;

    /** "newest 2026-08-17 12:00 UTC, 59 d 3 h old"; hours only under a day. Pure. */
    public static String ageText(long newestMilli, long nowMilli) {
        long age = Math.max(0, nowMilli - newestMilli);
        long days = age / TimeUtils.DAY_IN_MILLIS, hours = age % TimeUtils.DAY_IN_MILLIS / 3_600_000;
        String old = days > 0 ? days + " d " + hours + " h old" : hours + " h old";
        return "newest " + TimeUtils.formatDate(newestMilli) + " " + TimeUtils.formatTime(newestMilli).substring(0, 5) + " UTC, " + old;
    }

    public static boolean stale(long newestMilli, long nowMilli) {
        return nowMilli - newestMilli > STALE_MILLIS;
    }

    /** Opens the newest day of {@code product} at {@code level} (24 h from its start). */
    public static void open(String level, String product, java.util.function.Consumer<String> report) {
        PunchClient.submitGetCoverage(new PunchClient.ReceiverCoverage() {
            @Override
            public void setPunchResponseCoverage(long newestDay) {
                if (newestDay <= 0) {
                    report.accept("The PUNCH archive lists no " + product + " frames.");
                    return;
                }
                long end = newestDay + TimeUtils.DAY_IN_MILLIS - 1;
                PunchClient.submitSearchTime(new PunchClient.ReceiverItems() {
                    @Override
                    public void setPunchResponseItems(List<PunchClient.DataItem> items) {
                        load(items, level, product, newestDay, end, report);
                    }

                    @Override
                    public void setPunchResponseFailed(String message) {
                        report.accept("PUNCH latest: " + message);
                    }
                }, level, product, newestDay, end, 0, PunchClient.LATEST_VERSION);
            }

            @Override
            public void setPunchResponseFailed(String message) {
                report.accept("PUNCH latest: " + message);
            }
        }, level, product);
    }

    private static void load(List<PunchClient.DataItem> items, String level, String product, long start, long end,
                             java.util.function.Consumer<String> report) {
        if (items.isEmpty()) {
            report.accept("No " + product + " frames on " + TimeUtils.formatDate(start) + ".");
            return;
        }
        Log.info("PUNCH latest: " + items.size() + " " + product + " frames of " + TimeUtils.formatDate(start));
        Display.setMapMode(MapMode.Helioradial);
        PunchClient.submitLoad(items, level, product, start, end, 0, PunchClient.LATEST_VERSION,
                layer -> layer.setFilter(ImageFilter.Type.RHEF));
    }

    private PunchLatest() {}
}
