package org.helioviewer.jhv.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;

import javax.swing.JPanel;

/**
 * In the presenter window, every sidebar that goes in gets a place of its own.
 *
 * <p>Both used to be added at BorderLayout.CENTER, which takes exactly one component: the second
 * one added wins the constraint, and the first is laid out at zero by zero while staying a child
 * of the container. In the main window there is a canvas between the two sidebars and no way to
 * reach that state; in the presenter window the canvas is on the projector, so what was left was
 * one bar with everything crammed into it and the other missing or painting over it.
 *
 * <p>The assertions are the two halves of that: nothing is laid out at nothing, and no two of them
 * occupy the same pixels. Both fail against the old arrangement, the first at zero width.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.gui.PresenterChromeCheck
 */
public final class PresenterChromeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static JPanel bar(String name, int width) {
        JPanel p = new JPanel();
        p.setName(name);
        p.setPreferredSize(new Dimension(width, 400));
        p.setMinimumSize(new Dimension(40, 40));
        return p;
    }

    /** Lay out the whole tree by hand: nothing here is realized, so validate() does not cascade. */
    private static void layOut(Component c) {
        if (c instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents())
                layOut(child);
        }
    }

    private static void place(List<Component> fillers, int w, int h) {
        JPanel content = new JPanel(new BorderLayout());
        PresentationMode.placeFillers(content, fillers);
        content.setSize(w, h);
        layOut(content);
    }

    /** Where a component ended up in the window, whatever depth it was nested at. */
    private static Rectangle inWindow(Component c) {
        Rectangle r = c.getBounds();
        for (Container p = c.getParent(); p != null; p = p.getParent()) {
            r.x += p.getX();
            r.y += p.getY();
        }
        return r;
    }

    public static void main(String[] args) {
        Component west = bar("west", 320);
        Component east = bar("east", 300);

        // The presenter window as it is actually sized: a third of a screen, full height.
        place(List.of(west, east), 420, 900);
        Rectangle w = inWindow(west);
        Rectangle e = inWindow(east);
        System.out.println("       west=" + w + "  east=" + e);
        expect("the left sidebar is given real area", w.width > 0 && w.height > 0);
        expect("so is the right one", e.width > 0 && e.height > 0);
        expect("and they do not sit on top of one another", !w.intersects(e));
        expect("both get the full width of a narrow window, rather than half of it each",
                w.width > 300 && e.width > 300);
        expect("and between them they use the height", w.height + e.height > 700);

        // One sidebar, which is the ordinary case: nothing docked on the right.
        Component only = bar("only", 320);
        place(List.of(only), 420, 900);
        Rectangle o = inWindow(only);
        expect("a single sidebar fills the window on its own", o.width == 420 && o.height > 800);

        // None at all must not throw: a presenter window can legitimately be toolbar-only.
        place(List.of(), 420, 900);
        expect("no sidebars at all is not a crash", true);

        System.out.println(failures == 0 ? "PresenterChromeCheck: PASS" : "PresenterChromeCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PresenterChromeCheck() {}

}
