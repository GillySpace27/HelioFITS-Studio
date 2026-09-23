package org.helioviewer.jhv.gui.component;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseWheelListener;

import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * A scroll over the sidebar scrolls the sidebar.
 *
 * <p>Swing delivers a MouseWheelEvent to the deepest component under the pointer that has a
 * listener, and every JHV slider and spinner used to install one (WheelSupport, inherited from
 * JIDE). The sidebars are made of those controls, so a scroll that crossed them was eaten one
 * control at a time: the panel stayed where it was and the opacity, the gamma and the mask all
 * moved instead. A scroll gesture that silently edits the picture is worse than one that does
 * nothing, and this was both.
 *
 * <p>With no listener the event walks up to the enclosing JScrollPane, which is what the gesture
 * was aimed at. That walk is Swing's, not ours, so what is asserted here is the absence.
 *
 * <p>Run: java -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.gui.component.SidebarScrollCheck
 */
public final class SidebarScrollCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static void expectSilent(String what, Component c) {
        MouseWheelListener[] listeners = c.getMouseWheelListeners();
        expect(what + " lets the wheel through (" + listeners.length + " listeners)", listeners.length == 0);
    }

    public static void main(String[] args) {
        expectSilent("a slider", new JHVSlider(0, 100, 50));
        expectSilent("a spinner", new JHVSpinner(1, 1, 100, 1));
        // JHVRangeSlider had the same line removed, but it cannot be built here: its JIDE UI
        // reaches sun.swing, which the application opens on its own command line and a check
        // JVM does not. Its constructor is three lines long and sits next to the other two.

        // And the thing they are inside of still wants it, or there is nothing to scroll with.
        JScrollPane scroller = new JScrollPane(new JPanel());
        expect("the scroll pane still handles the wheel itself",
                scroller.getMouseWheelListeners().length > 0 || scroller.isWheelScrollingEnabled());

        // A panel of controls hands the whole gesture upward.
        JPanel sidebar = new JPanel();
        sidebar.add(new JHVSlider(0, 100, 50));
        sidebar.add(new JHVSpinner(1, 1, 100, 1));
        expect("nothing in a panel of them claims the wheel", noneListen(sidebar));

        if (failures != 0)
            throw new AssertionError(failures + " sidebar scroll failure(s)");
        System.out.println("SidebarScrollCheck: PASS");
    }

    private static boolean noneListen(Container container) {
        for (Component c : container.getComponents()) {
            if (c.getMouseWheelListeners().length > 0)
                return false;
            if (c instanceof Container inner && !noneListen(inner))
                return false;
        }
        return true;
    }

}
