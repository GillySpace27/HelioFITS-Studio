package org.helioviewer.jhv.io;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * The arithmetic around the cache scan: how a dataset stands against the master range, how frames
 * gather into datasets, and whether the stored index can be trusted to be about the files on disk.
 *
 * <p>Reading a FITS file is not exercised here and cannot be: {@code CommonMetaData} holds
 * {@code Sun.StartEarth} as a static field, so touching the metadata classes initialises SPICE,
 * which needs its native library and kernels. That is settled in the application long before this
 * code can run. What is left either side of that call is the part with decisions in it, and it is
 * all here.
 *
 * <p>The overlap rules are the ones the dialog paints with, so getting them wrong is not a subtle
 * failure: it is a green chip on a dataset that does not cover the range, and a download that the
 * whole feature exists to avoid.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.io.CacheIndexCheck
 */
public final class CacheIndexCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final long DAY = 86400_000L;
    private static final long R0 = 10 * DAY; // the master range, for readability below
    private static final long R1 = 20 * DAY;

    private static CacheIndex.Overlap at(long start, long end) {
        return CacheIndex.overlap(start, end, R0, R1);
    }

    private static CacheIndex.Frame frame(String dataset, long time, long bytes) {
        return new CacheIndex.Frame("f" + time, dataset, dataset, time, bytes);
    }

    public static void main(String[] args) throws Exception {
        // -- overlap, which is what the three colours mean -------------------------------------
        expect("a dataset spanning the whole range covers it", at(5 * DAY, 25 * DAY) == CacheIndex.Overlap.COVERS);
        expect("and so does one that matches it exactly", at(R0, R1) == CacheIndex.Overlap.COVERS);
        expect("one that starts inside is partial, not covering",
                at(12 * DAY, 25 * DAY) == CacheIndex.Overlap.PARTIAL);
        expect("so is one that ends inside", at(5 * DAY, 15 * DAY) == CacheIndex.Overlap.PARTIAL);
        expect("and one wholly inside is partial too, which is the point of coverage over containment",
                at(12 * DAY, 15 * DAY) == CacheIndex.Overlap.PARTIAL);
        expect("one entirely before does not overlap", at(1 * DAY, 5 * DAY) == CacheIndex.Overlap.NONE);
        expect("nor does one entirely after", at(25 * DAY, 30 * DAY) == CacheIndex.Overlap.NONE);
        // Touching is not overlapping: one frame at the boundary plays nothing.
        expect("a dataset ending exactly where the range starts does not overlap",
                at(5 * DAY, R0) == CacheIndex.Overlap.NONE);
        expect("nor one starting exactly where it ends", at(R1, 25 * DAY) == CacheIndex.Overlap.NONE);
        // A single-frame dataset has no span to share, so the touching rule has to spare it: it is
        // in the range or it is not, and greying out a frame sitting squarely inside would be a
        // wrong label rather than a cautious one.
        expect("a lone frame inside the range counts as partial", at(15 * DAY, 15 * DAY) == CacheIndex.Overlap.PARTIAL);
        expect("a lone frame outside it does not", at(30 * DAY, 30 * DAY) == CacheIndex.Overlap.NONE);
        expect("an empty master range never reports covering",
                CacheIndex.overlap(0, 100 * DAY, R0, R0) == CacheIndex.Overlap.NONE);
        expect("nor does it report anything else", CacheIndex.overlap(R0, R0, R0, R0) == CacheIndex.Overlap.NONE);
        expect("a span running backwards is refused rather than inverted",
                CacheIndex.overlap(25 * DAY, 5 * DAY, R0, R1) == CacheIndex.Overlap.NONE);

        // -- coverage, which is the number the partial rows print --------------------------------
        expect("covering the whole range reads as 1", CacheIndex.coverage(0, 100 * DAY, R0, R1) == 1);
        expect("half the range reads as 0.5", CacheIndex.coverage(R0, 15 * DAY, R0, R1) == 0.5);
        expect("no overlap reads as 0", CacheIndex.coverage(0, 5 * DAY, R0, R1) == 0);
        expect("touching reads as 0 rather than as a sliver", CacheIndex.coverage(0, R0, R0, R1) == 0);
        expect("an empty range reads as 0 rather than dividing by nothing",
                CacheIndex.coverage(0, 100 * DAY, R0, R0) == 0);

        // -- grouping ---------------------------------------------------------------------------
        List<CacheIndex.Dataset> sets = CacheIndex.group(List.of(
                frame("small", 5 * DAY, 10),
                frame("big", 3 * DAY, 4000),
                frame("small", 1 * DAY, 10),
                frame("big", 1 * DAY, 4000),
                frame("big", 2 * DAY, 4000)));
        expect("frames gather by dataset", sets.size() == 2);
        expect("the biggest on disk is listed first", sets.getFirst().key().equals("big"));
        expect("its frames are counted", sets.getFirst().frameCount() == 3);
        expect("and its bytes added up", sets.getFirst().bytes() == 12000);
        expect("frames arrive out of order and come out in time order",
                sets.getFirst().frames().getFirst().time() == 1 * DAY
                        && sets.getFirst().frames().getLast().time() == 3 * DAY);
        expect("the span is first to last, whatever order they were read in",
                sets.getFirst().start() == 1 * DAY && sets.getFirst().end() == 3 * DAY);
        expect("cadence is the median gap", sets.getFirst().cadence() == DAY);
        expect("a dataset of one has no cadence to report", CacheIndex.group(List.of(frame("lone", 0, 1)))
                .getFirst().cadence() == 0);
        expect("nothing cached is not a crash", CacheIndex.group(List.of()).isEmpty());

        // An uneven cadence takes the median, not the mean: one long gap in a regular movie is a
        // hole in the data, and reporting it as the cadence would misdescribe every other frame.
        CacheIndex.Dataset gappy = CacheIndex.group(List.of(
                frame("g", 0, 1), frame("g", DAY, 1), frame("g", 2 * DAY, 1), frame("g", 60 * DAY, 1))).getFirst();
        expect("one long gap does not become the cadence", gappy.cadence() == DAY);

        // -- the stored index -------------------------------------------------------------------
        System.setProperty("user.home", Files.createTempDirectory("jhv-cache-index").toString());
        org.helioviewer.jhv.app.Platform.init();
        Directories.createPersistentDirs();

        Map<String, CacheIndex.Frame> written = new java.util.LinkedHashMap<>();
        written.put("a:1:2", frame("PUNCH · L3 · PA · v0l", 7 * DAY, 512));
        written.put("b:3:4", frame("SOHO · LASCO · C2", 8 * DAY, 64));
        CacheIndex.write(written);

        Map<String, CacheIndex.Frame> back = CacheIndex.read();
        expect("what was written comes back", back.size() == 2);
        expect("keyed on the stamp it was stored under", back.containsKey("a:1:2") && back.containsKey("b:3:4"));
        expect("with the dataset intact, dots and all",
                "PUNCH · L3 · PA · v0l".equals(back.get("a:1:2").dataset()));
        expect("and the observation time intact", back.get("a:1:2").time() == 7 * DAY);
        expect("a file whose size or date changed is not in the index under its new stamp",
                !back.containsKey("a:1:9"));

        System.out.println(failures == 0 ? "CacheIndexCheck: PASS" : "CacheIndexCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private CacheIndexCheck() {}

}
