package org.helioviewer.jhv.layers;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;

/** When the next piece of a long request is asked for: the Setting, and how far along the previous piece must be. */
public final class ChunkLoadingCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-chunk-mode").toString());
        System.setProperty("java.awt.headless", "true");
        Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.io.Directories.SETTINGS.getFile().mkdirs();

        expect("by default the next piece is asked for as soon as the previous one opens", ChunkLoading.current() == ChunkLoading.Mode.OPEN);
        Settings.setProperty(ChunkLoading.KEY, "SEQUENTIAL");
        expect("the Setting is read", ChunkLoading.current() == ChunkLoading.Mode.SEQUENTIAL);
        Settings.setProperty(ChunkLoading.KEY, "nonsense");
        expect("an unknown value is the default", ChunkLoading.current() == ChunkLoading.Mode.OPEN);

        expect("open and all never wait", ChunkLoading.reached(ChunkLoading.Mode.OPEN, 0, 646) && ChunkLoading.reached(ChunkLoading.Mode.ALL, 0, 646));
        expect("80% of 646 frames is 517", !ChunkLoading.reached(ChunkLoading.Mode.MOSTLY, 516, 646) && ChunkLoading.reached(ChunkLoading.Mode.MOSTLY, 517, 646));
        expect("sequential waits for every frame", !ChunkLoading.reached(ChunkLoading.Mode.SEQUENTIAL, 645, 646) && ChunkLoading.reached(ChunkLoading.Mode.SEQUENTIAL, 646, 646));
        expect("a one-frame piece does not wait for ever", ChunkLoading.reached(ChunkLoading.Mode.MOSTLY, 1, 1));

        System.out.println(failures == 0 ? "ChunkLoadingCheck: PASS" : "ChunkLoadingCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private ChunkLoadingCheck() {}

}
