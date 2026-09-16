package org.helioviewer.jhv.app;

import java.nio.file.Files;

/**
 * Framing a newly opened dataset is on unless it has been turned off.
 *
 * <p>The setting is stored as text and read back at startup, where the absent case is the one that
 * matters: a preferences file written before this existed, or a fresh install, must arrive with
 * the framing on rather than off. Boolean.parseBoolean says false to anything it does not
 * recognise, including nothing at all, so the default cannot be left to it.
 *
 * <p>Run: java -cp "bin:extra/test-classes" org.helioviewer.jhv.app.AutoResetViewCheck
 */
public final class AutoResetViewCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-auto-reset").toString());
        Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();

        expect("a fresh install frames a newly opened dataset", DisplaySettings.getAutoResetView());

        DisplaySettings.setAutoResetView(false);
        expect("turning it off is remembered", !DisplaySettings.getAutoResetView());
        expect("and is written as text a later run can read",
                "false".equals(Settings.getProperty("display.autoResetView")));

        DisplaySettings.setAutoResetView(true);
        expect("turning it back on is remembered", DisplaySettings.getAutoResetView());

        // How the stored value reads at startup, which is where the absent case decides the default.
        expect("a preferences file from before this setting reads as on", DisplaySettings.parseAutoResetView(null));
        expect("an empty value reads as on", DisplaySettings.parseAutoResetView(""));
        expect("an off value reads as off", !DisplaySettings.parseAutoResetView("false"));
        expect("an on value reads as on", DisplaySettings.parseAutoResetView("true"));
        expect("anything unrecognised reads as off, as everywhere else in these settings",
                !DisplaySettings.parseAutoResetView("maybe"));

        System.out.println(failures == 0 ? "AutoResetViewCheck: PASS" : "AutoResetViewCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private AutoResetViewCheck() {}

}
