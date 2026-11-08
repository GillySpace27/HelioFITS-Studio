package org.helioviewer.jhv.app;

import org.helioviewer.jhv.io.Directories;

/**
 * A layer restored from a session must not reset the view, whatever "Reset the view when a layer loads" says.
 *
 * <p>ImageLayer.setView reset the camera on every layer's first frame while that setting (on by default)
 * was on, restored layers included, so a session or an export record opened with the default setting came
 * back at the default zoom instead of the saved one. Found replaying a movie export with
 * pyheliofits.reproduce: zoom 2 saved, zoom 1 after the load, every frame digest different. The camera
 * itself restored correctly with the setting off.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.AutoResetViewCheck
 */
public final class AutoResetViewCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-autoreset").toString());
        Platform.init();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs();

        DisplaySettings.setAutoResetView(true);
        expect("an interactive layer resets the view when the setting is on", DisplaySettings.resetsViewOnLoad(false));
        expect("a layer restored from a session does not, though the setting is on", !DisplaySettings.resetsViewOnLoad(true));
        DisplaySettings.setAutoResetView(false);
        expect("with the setting off nothing resets", !DisplaySettings.resetsViewOnLoad(false) && !DisplaySettings.resetsViewOnLoad(true));

        if (failures > 0) {
            System.out.println("AutoResetViewCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("AutoResetViewCheck: all passed");
    }
}
