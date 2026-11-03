package org.helioviewer.jhv.timelines.draw;

import org.helioviewer.jhv.time.TimeUtils;

/**
 * Timeline zoom to fit: the window it asks for holds the whole loaded span with room either side,
 * and a single frame (no span of its own) still gets an hour of context.
 */
public final class TimelineZoomFitCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        long day = TimeUtils.DAY_IN_MILLIS;
        long first = 1_700_000_000_000L;

        long[] week = DrawController.fitInterval(first, first + 7 * day);
        expect("a week: the window starts before the first frame", week[0] < first);
        expect("a week: the window ends after the last frame", week[1] > first + 7 * day);
        expect("a week: the room either side is 5% of the span", week[0] == first - 7 * day / 20 && week[1] == first + 7 * day + 7 * day / 20);

        long[] one = DrawController.fitInterval(first, first);
        expect("one frame: an hour of context either side", one[0] == first - 60 * TimeUtils.MINUTE_IN_MILLIS && one[1] == first + 60 * TimeUtils.MINUTE_IN_MILLIS);

        long[] short_ = DrawController.fitInterval(first, first + 10 * TimeUtils.MINUTE_IN_MILLIS);
        expect("ten minutes: the hour floor applies", short_[1] - short_[0] == 120 * TimeUtils.MINUTE_IN_MILLIS + 10 * TimeUtils.MINUTE_IN_MILLIS);

        if (failures > 0) {
            System.out.println("TimelineZoomFitCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("TimelineZoomFitCheck: all ok");
    }
}
