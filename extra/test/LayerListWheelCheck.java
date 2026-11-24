package org.helioviewer.jhv.gui.component;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * A scroll over a layer list scrolls the sidebar. AWT hands a wheel event to the deepest component
 * under the pointer that has a wheel listener, and a JScrollPane's look and feel always installs
 * one; setWheelScrollingEnabled(false) only makes that listener ignore the event, so the event was
 * swallowed and the sidebar stopped wherever a list (the 3D Scene list, the image layers) passed
 * under the pointer (seen with Robot under Xvfb, 2026-11-23). LayersPanel's pane is a
 * PassWheelScrollPane, which carries no wheel listener at all, also after a theme switch.
 * (LayersPanel itself cannot be built headless: DesktopIntegration asks the toolkit for keys.)
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.LayerListWheelCheck
 */
public final class LayerListWheelCheck {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        JScrollPane plain = new JScrollPane(new JTable(3, 3));
        plain.setWheelScrollingEnabled(false);
        check(plain.getMouseWheelListeners().length > 0,
                "a plain JScrollPane keeps its wheel listener with wheel scrolling off (the trap this answers)");

        JScrollPane pane = new PassWheelScrollPane(new JTable(3, 3), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        check(pane.getMouseWheelListeners().length == 0, "the list's pane has no wheel listener, so the sidebar gets the wheel");
        check(!pane.isWheelScrollingEnabled(), "and does not scroll itself");
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        SwingUtilities.updateComponentTreeUI(pane);
        check(pane.getMouseWheelListeners().length == 0, "still none after a theme switch reinstalls its UI");
        java.awt.event.MouseWheelListener own = e -> { };
        pane.addMouseWheelListener(own);
        SwingUtilities.updateComponentTreeUI(pane);
        check(pane.getMouseWheelListeners().length == 1 && pane.getMouseWheelListeners()[0] == own,
                "a theme switch keeps a listener a caller added, and only that one");
        pane.removeMouseWheelListener(own);
        check(pane.getViewport().getMouseWheelListeners().length == 0 && pane.getViewport().getView().getMouseWheelListeners().length == 0,
                "nor in its viewport or the table");

        // The Track comet and Track CME lists sit in the sidebar too (Gilly, 2026-11-24: they still caught the wheel)
        for (String panel : new String[]{"CometTrackPanel", "CactusTrackPanel"}) {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/org/helioviewer/jhv/event/info/" + panel + ".java"));
            check(src.contains("new PassWheelScrollPane(") && !src.contains("new JScrollPane("), panel + " lists in a PassWheelScrollPane, not a plain JScrollPane");
        }

        System.out.println(failures == 0 ? "LayerListWheelCheck: PASS" : "LayerListWheelCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private LayerListWheelCheck() {}
}
