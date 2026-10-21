package org.helioviewer.jhv.timelines.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;

import javax.swing.JButton;
import javax.swing.JScrollPane;

import org.helioviewer.jhv.timelines.TimelineLayers;

/**
 * The Timeline Layers panel still lays out when its section gives it a pixel less than it asks for.
 *
 * <p>Seen live on 2026-10-20 in a probe of the running app: the section gave the panel 144 px for
 * 145, GridBagLayout fell back to minimum sizes, and the toolbar-over-table box shrank to 32 px with
 * the toolbar at -89 px tall and the table drawn above its own top. The section looked empty.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.timelines.gui.TimelinePanelShortCheck
 */
public final class TimelinePanelShortCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static void layout(Component c) {
        c.doLayout();
        if (c instanceof Container k)
            for (Component child : k.getComponents())
                layout(child);
    }

    private static <T extends Component> T find(Component c, Class<T> type) {
        if (type.isInstance(c))
            return type.cast(c);
        if (c instanceof Container k)
            for (Component child : k.getComponents()) {
                T t = find(child, type);
                if (t != null)
                    return t;
            }
        return null;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", System.getProperty("java.io.tmpdir")); // Settings must not touch the real home
        // The app registers the icon font at start-up; the panel's buttons need it (as GlyphIconCheck).
        try (java.io.InputStream is = org.helioviewer.jhv.io.FileUtils.getResource("/fonts/materialdesignicons-webfont.ttf")) {
            org.helioviewer.jhv.gui.UIGlobals.uiFontMDI = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, is);
        }
        TimelinePanel panel = new TimelinePanel(new TimelineLayers());
        Dimension pref = panel.getPreferredSize();
        for (int shortBy : new int[]{0, 1, 2}) {
            panel.setSize(469, pref.height - shortBy);
            layout(panel);
            JButton newLayer = find(panel, JButton.class);
            JScrollPane table = find(panel, JScrollPane.class);
            expect(shortBy + " px short: the New Layer button has its height",
                    newLayer != null && newLayer.getParent().getHeight() > 0);
            expect(shortBy + " px short: the table starts inside the panel, not above it",
                    table != null && table.getY() >= 0);
        }
        System.out.println(failures == 0 ? "TimelinePanelShortCheck: ok" : "TimelinePanelShortCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
