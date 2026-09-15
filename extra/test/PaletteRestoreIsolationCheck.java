package org.helioviewer.jhv.gui.component;

import java.awt.Component;

import javax.swing.JPanel;

import org.helioviewer.jhv.app.Settings;

/**
 * A palette that fails to restore cannot keep the others out of the sidebar.
 *
 * <p>Palette.restoreOpen docks every remembered palette in one loop. On 2026-09-14 the newly renamed
 * Filters palette threw while building its content (it bound RHEF to the placeholder layer a restoring
 * session starts with), the loop ended there, and Grid and Annotation, which came after it, never
 * appeared in the right sidebar at all.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.PaletteRestoreIsolationCheck
 */
public final class PaletteRestoreIsolationCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-palette-restore").toString());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(org.helioviewer.jhv.io.Directories.SETTINGS.getPath()));

        Settings.setProperty("ui.palette.Broken.sidebar", "right");
        Settings.setProperty("ui.palette.Fine.sidebar", "right");
        new Palette("Broken", () -> {
            throw new IllegalStateException("content cannot be built"); // as Filters did over a layer with no GLImage
        }, () -> {});
        new Palette("Fine", () -> (Component) new JPanel(), () -> {});

        boolean threw = false;
        try {
            Palette.restoreOpen();
        } catch (RuntimeException e) {
            threw = true;
        }
        expect("restoring does not throw because one palette did", !threw);
        expect("and the palette after the broken one is in the sidebar", RightSidebar.getInstance().hasSection("Fine"));

        System.out.println(failures == 0 ? "PaletteRestoreIsolationCheck: PASS" : "PaletteRestoreIsolationCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PaletteRestoreIsolationCheck() {}
}
