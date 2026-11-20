package org.helioviewer.jhv.gui.component;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * Every right-sidebar section carries a glyph in front of its title, so the L1/R1 numbers after it
 * line up in one column (Gilly, 2026-11-19). Identify and Track CME dock before their toolbar buttons
 * are bound and Track comet has none, so each brings its own; without one the header shows the
 * chevron alone and its number sits a glyph's width to the left of the others.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.SidebarGlyphCheck
 */
public final class SidebarGlyphCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static List<CollapsiblePane> panes(Container root) {
        List<CollapsiblePane> out = new ArrayList<>();
        for (Component c : root.getComponents())
            if (c instanceof CollapsiblePane p)
                out.add(p);
        return out;
    }

    private static CollapsiblePaneButton header(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof CollapsiblePaneButton b)
                return b;
            if (c instanceof Container k) {
                CollapsiblePaneButton b = header(k);
                if (b != null)
                    return b;
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-sidebar-glyph").toString()); // never the real settings
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        java.awt.EventQueue.invokeAndWait(() -> {
            IdentifyPalette.palette();
            org.helioviewer.jhv.event.info.CactusTrackPanel.palette();
            org.helioviewer.jhv.event.info.CometTrackPanel.palette();

            List<CollapsiblePane> panes = panes(RightSidebar.getInstance().pane());
            for (String title : List.of("Identify", "Track CME", "Track comet")) {
                CollapsiblePane pane = panes.stream().filter(p -> title.equals(p.getTitle())).findFirst().orElse(null);
                expect(title + " is a section of the right sidebar", pane != null);
                CollapsiblePaneButton b = pane == null ? null : header(pane);
                int chevron = Buttons.chevronRight.getIconWidth();
                expect(title + "'s header shows a glyph after its chevron",
                        b != null && b.getIcon() != null && b.getIcon().getIconWidth() > chevron);
            }
        });

        System.out.println(failures == 0 ? "SidebarGlyphCheck: PASS" : "SidebarGlyphCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private SidebarGlyphCheck() {}
}
