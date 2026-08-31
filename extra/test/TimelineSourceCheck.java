package org.helioviewer.jhv.layers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.helioviewer.jhv.time.JHVTime;

/**
 * A layer that carries its own time series must be offerable as the movie's master.
 *
 * <p>The bug this closes: a point cloud loaded one file per epoch could only drive the clock when
 * nothing else was loaded, because Layers.setPlaceholderMasterTimes is a no-op the moment any
 * image layer appears. With imagery from a different epoch, every frame of the cloud resolved to
 * whichever one sat nearest the imagery's time, so the series sat frozen on one frame. That looks
 * exactly like a layer that failed to load, and it was diagnosed as one twice.
 *
 * <p>The eligibility rule is the part worth pinning. One timestamp is not a series, and a master
 * with a single frame freezes playback, so a source has to carry at least two before it is
 * offered. The layer table keys its radio button off exactly this, so getting it wrong either
 * hides the control or offers one that cannot work.
 *
 * <p>Run: java -cp extra/test-classes:bin org.helioviewer.jhv.layers.TimelineSourceCheck
 */
public final class TimelineSourceCheck {

    private static int failures;

    private record Fake(List<JHVTime> times) implements TimelineSource {
        @Override
        public Collection<JHVTime> getTimelineTimes() {
            return times;
        }
    }

    public static void main(String[] args) {
        if (System.getProperty("user.timezone") == null) // TimeUtils' static init requires it; see JHVMetadataDump precedent
            System.setProperty("user.timezone", "UTC");

        expect(!new Fake(List.of()).canDriveTimeline(), "no timestamps cannot drive the timeline");
        expect(!new Fake(List.of(t("2021-10-28T16:00:00"))).canDriveTimeline(),
               "a single instant is not a series");
        expect(new Fake(List.of(t("2021-10-28T16:00:00"), t("2021-10-28T16:15:00"))).canDriveTimeline(),
               "two timestamps are enough to animate");

        // The real shape of the case: eleven epochs a quarter of an hour apart, which is the
        // series that was sitting frozen.
        List<JHVTime> series = new ArrayList<>();
        for (int i = 0; i < 11; i++)
            series.add(new JHVTime(t("2021-10-28T16:00:00").milli + i * 900_000L));
        Fake cloud = new Fake(series);
        expect(cloud.canDriveTimeline(), "an eleven-frame cloud can drive the timeline");
        equal(cloud.getTimelineTimes().size(), 11, "all eleven frames are offered");

        // Distinctness matters: if the loader ever collapsed every file onto one timestamp the
        // collection would shrink, and the layer would silently stop being eligible.
        equal((int) series.stream().distinct().count(), 11, "the frames are distinct instants");

        // The default is derived from the collection, not stored, so a source cannot report
        // itself drivable while handing back nothing to drive with.
        for (int n : new int[]{0, 1, 2, 5}) {
            List<JHVTime> times = new ArrayList<>();
            for (int i = 0; i < n; i++)
                times.add(new JHVTime(t("2021-10-28T16:00:00").milli + i * 900_000L));
            Fake f = new Fake(times);
            expect(f.canDriveTimeline() == (f.getTimelineTimes().size() > 1),
                   "eligibility follows the frame count at n=" + n);
        }

        System.out.println(failures == 0 ? "TimelineSourceCheck: PASS" : "TimelineSourceCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static JHVTime t(String s) {
        return new JHVTime(s);
    }

    private static void equal(int got, int want, String what) {
        if (got != want) {
            System.out.println("FAIL: " + what + " -- got " + got + ", want " + want);
            failures++;
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private TimelineSourceCheck() {}
}
