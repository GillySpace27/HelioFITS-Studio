package org.helioviewer.jhv.app;

import java.nio.file.Files;
import java.nio.file.Path;

import org.helioviewer.jhv.io.Directories;

/**
 * A process that never read the settings file never writes over it.
 *
 * <p>Settings writes the whole table on every change. The application loads the file before anything
 * sets a key, but the checks in extra/test run the same classes in JVMs that never call load(), and
 * most of them do not isolate user.home. The first key one of them set was written out as the entire
 * file: on 2026-09-11 Gilly's user.properties was found as a single display.skyBase line, with the
 * toolbar order, the sidebars and every palette's home gone. That is the dashboard that would not stay
 * the way he set it.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.SettingsWriteGuardCheck
 */
public final class SettingsWriteGuardCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // Before the first mention of Directories or Settings: both capture user.home when loaded.
        System.setProperty("user.home", Files.createTempDirectory("jhv-settings-guard").toString());

        Path file = Path.of(Directories.SETTINGS.getPath(), "user.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ui.toolbar.order=projection|trackCme\nui.palette.Grid.sidebar=right\n");
        String before = Files.readString(file);

        // What a check does: touch a component that records a preference, never having loaded.
        Settings.setProperty("display.skyBase", "Orthographic");
        expect("unloaded, the file is not touched", Files.readString(file).equals(before));
        expect("but the value is still there for the process that set it",
                "Orthographic".equals(Settings.getProperty("display.skyBase")));

        // What the application does, in its order: load() validates the stored data server against
        // the sources, so they come first, exactly as in HFStudio.main.
        org.helioviewer.jhv.io.DataSources.initSources();
        Settings.load();
        Settings.setProperty("ui.palette.Track_CME.sidebar", "right");
        String after = Files.readString(file);
        expect("loaded, a write goes through", after.contains("ui.palette.Track_CME.sidebar=right"));
        expect("and keeps what was already there", after.contains("ui.toolbar.order=projection|trackCme")
                && after.contains("ui.palette.Grid.sidebar=right"));
        Path daily = file.resolveSibling("user.properties." + java.time.LocalDate.now());
        expect("and a load keeps a copy for the day, as it was read", Files.exists(daily)
                && Files.readString(daily).equals(before));

        System.out.println(failures == 0 ? "SettingsWriteGuardCheck: PASS" : "SettingsWriteGuardCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private SettingsWriteGuardCheck() {}
}
