package org.helioviewer.jhv.gui.search;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * The Getting Started tour's sample images ship and unpack: each listed sample is on the classpath,
 * copies out to a folder once and is reused after, and has a row in resources/samples/README.md
 * saying where it came from and under what terms (PUNCH team, 2026-10-07).
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.search.TourSamplesCheck
 */
public final class TourSamplesCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        expect("at least one sample is listed", !TourSamples.NAMES.isEmpty());
        String readme = Files.readString(new File("resources/samples/README.md").toPath());
        for (String name : TourSamples.NAMES) {
            expect(name + " is on the classpath", TourSamplesCheck.class.getResource("/samples/" + name) != null);
            expect(name + " has a row in resources/samples/README.md", readme.contains("| `" + name + "` |"));
        }

        File dir = Files.createTempDirectory("hfs-tour-samples").toFile();
        List<File> first = TourSamples.extract(dir);
        boolean all = first.size() == TourSamples.NAMES.size();
        for (File f : first)
            all &= f.isFile() && f.length() > 2880 && f.getParentFile().equals(dir);
        expect("each sample unpacks to a non-empty file in the folder", all);
        String[] left = dir.list((d, n) -> n.endsWith(".part"));
        expect("no partial copy is left behind", left != null && left.length == 0);

        long stamp = first.get(0).lastModified() - 60_000;
        first.get(0).setLastModified(stamp);
        TourSamples.extract(dir);
        expect("a complete copy is reused, not written again", first.get(0).lastModified() == stamp);

        System.out.println(failures == 0 ? "TourSamplesCheck: all ok" : "TourSamplesCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

}
