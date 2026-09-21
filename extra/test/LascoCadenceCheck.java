package org.helioviewer.jhv.io;

/**
 * What a cadence does to a LASCO frame count.
 *
 * <p>Asked for a week of C2, the layer came back with about a hundred frames while a PUNCH layer over
 * the same range came back with a thousand, which reads as a cap on LASCO. There is none: the buttons
 * were asking for a cadence aimed at 96 frames whatever the Time step / Frame count control said. The
 * arithmetic underneath is still worth pinning down, because it is per day rather than per range: a
 * day's files are sampled to meet a per-day quota, so "get all" means every frame the archive holds
 * and no frame count can exceed that.
 *
 * <p>File counts are real LZ days: 106 to 114 frames per telescope, from the 2025-08 and 2025-09 runs.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.io.LascoCadenceCheck
 */
public final class LascoCadenceCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    // Frames kept from one day of the archive at a given cadence.
    private static int kept(long cadenceMillis, int filesThatDay) {
        int step = LascoClient.step(cadenceMillis, filesThatDay);
        return (filesThatDay + step - 1) / step;
    }

    public static void main(String[] args) {
        expect("get all (cadence 0) keeps every frame of a 112 file day, got " + kept(0, 112),
                kept(0, 112) == 112);
        expect("and a negative cadence, which is how the panel spells \"get all\", does the same",
                kept(-100_000, 112) == 112);

        // The default the LASCO buttons used to force: 96 frames over the range, so 90 min on a week.
        expect("90 min keeps 16 of a 112 file day, got " + kept(90 * 60_000L, 112),
                kept(90 * 60_000L, 112) == 16);
        expect("which over six days is the ~100 frames that looked like a cap, got " + 6 * kept(90 * 60_000L, 112),
                6 * kept(90 * 60_000L, 112) == 96);

        // Asking for more than the archive holds is not an error, it is just the archive.
        expect("a 1 min cadence cannot conjure more than the 112 frames the day has, got " + kept(60_000L, 112),
                kept(60_000L, 112) == 112);
        expect("nor can a 1 s one", kept(1_000L, 106) == 106);

        // A cadence coarser than a day still leaves one frame a day, never none.
        expect("a 3 day cadence still keeps one frame from each day, got " + kept(3 * 86_400_000L, 114),
                kept(3 * 86_400_000L, 114) == 1);

        expect("a day the archive has nothing for keeps nothing", kept(0, 0) == 0);

        System.out.println(failures == 0 ? "LascoCadenceCheck: ok" : "LascoCadenceCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
