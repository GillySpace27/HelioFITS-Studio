package org.helioviewer.jhv.app;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The shipped default layout: only layout keys come from it, a fresh home gets them, the user's
 * own value wins, and none of it is written into the user's settings file.
 *
 * <p>Gilly, 2026-10-06: a fresh home opened with the palettes hidden and the toolbar in upstream's
 * order, and his arrangement had to be rebuilt by hand. The preset is the fix; the dangers are a
 * preset that carries something other than layout (a path, a server, a token) to every user, and
 * one that leaks into user.properties and then can never be changed by shipping a new preset.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.LayoutPresetCheck
 */
public final class LayoutPresetCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // Settings writes: never into the real home (see Settings.loaded).
        Path home = Files.createTempDirectory("hfs-layout-preset");
        System.setProperty("user.home", home.toString());
        Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();

        expect("toolbar, palette and section keys are layout", Settings.isLayoutKey("ui.toolbar.order")
                && Settings.isLayoutKey("ui.palette.Projection") && Settings.isLayoutKey("ui.section.Image_Layers"));
        expect("the panel lock and the sidebars are layout", Settings.isLayoutKey("ui.panelsLocked")
                && Settings.isLayoutKey("ui.rightSidebarOrder") && Settings.isLayoutKey("ui.sidebarCollapsed"));
        expect("a download path is not", !Settings.isLayoutKey("path.local"));
        expect("nor a data server", !Settings.isLayoutKey("dataSources.defaultServer"));
        expect("nor the window's position, which belongs to one person's screens", !Settings.isLayoutKey("ui.windowBounds"));
        expect("nor whether the tour was offered", !Settings.isLayoutKey("ui.tourOffered"));

        Properties preset = new Properties();
        try (InputStream in = Settings.class.getResourceAsStream(Settings.LAYOUT_PRESET)) {
            expect("the preset is on the classpath (" + Settings.LAYOUT_PRESET + ")", in != null);
            if (in != null)
                preset.load(in);
        }
        for (String key : preset.stringPropertyNames())
            expect("the preset carries only layout: " + key, Settings.isLayoutKey(key));
        System.out.println("  (" + preset.size() + " keys in the preset)");

        org.helioviewer.jhv.io.DataSources.initSources(); // load() checks the data server against them, as in HFStudio.main
        Settings.load(); // a fresh home: no user.properties
        boolean all = true;
        for (String key : preset.stringPropertyNames())
            all &= preset.getProperty(key).equals(Settings.getProperty(key));
        expect("a fresh home gets every preset value", all);

        Settings.setProperty("ui.panelsLocked", "user-choice");
        expect("the user's own value wins over the preset", "user-choice".equals(Settings.getProperty("ui.panelsLocked")));

        Properties written = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(org.helioviewer.jhv.io.Directories.SETTINGS.getPath(), "user.properties"))) {
            written.load(reader);
        }
        boolean leaked = false;
        for (String key : preset.stringPropertyNames())
            if (!key.equals("ui.panelsLocked") && written.containsKey(key))
                leaked = true;
        expect("no preset value is written into user.properties", !leaked);
        expect("the user's own value is", "user-choice".equals(written.getProperty("ui.panelsLocked")));

        if (failures > 0) {
            System.out.println("LayoutPresetCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("LayoutPresetCheck: all passed");
    }

    private LayoutPresetCheck() {}

}
