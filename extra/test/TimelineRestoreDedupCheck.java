package org.helioviewer.jhv.timelines;

import java.util.ArrayList;
import java.util.List;

/**
 * A session restore must not stack copies of the singleton coverage and bodies tracks. Every save
 * then reload used to add one more, until a session held thousands and the event thread spent
 * its life repainting them.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.timelines.TimelineRestoreDedupCheck
 */
public final class TimelineRestoreDedupCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static long count(Class<?> c) {
        return TimelineLayers.get().stream().filter(l -> l.getClass() == c).count();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-timeline-dedup").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice(); // the default layers reach SPICE

        TimelineLayers model = new TimelineLayers();
        model.restore(List.of(new CoverageTimelineLayer(), new BodiesTimelineLayer()));
        expect("one coverage and one bodies after a plain restore", count(CoverageTimelineLayer.class) == 1 && count(BodiesTimelineLayer.class) == 1);

        // The saved state carries what the previous run left; restoring it on top of the live
        // layers is the path that used to double the count each round.
        for (int round = 0; round < 5; round++) {
            List<TimelineLayer> saved = new ArrayList<>();
            for (TimelineLayer l : TimelineLayers.get())
                saved.add(l instanceof CoverageTimelineLayer ? new CoverageTimelineLayer() : l);
            model.restore(saved);
        }
        expect("repeated save and restore keeps one coverage", count(CoverageTimelineLayer.class) == 1);
        expect("and one bodies", count(BodiesTimelineLayer.class) == 1);

        List<TimelineLayer> bloated = new ArrayList<>();
        for (int i = 0; i < 500; i++)
            bloated.add(new CoverageTimelineLayer());
        model.restore(bloated);
        expect("a session already holding 500 copies collapses to one", count(CoverageTimelineLayer.class) == 1);
        System.exit(failures > 0 ? 1 : 0); // the coverage tracks' repaint timers would keep the JVM up
    }
}
