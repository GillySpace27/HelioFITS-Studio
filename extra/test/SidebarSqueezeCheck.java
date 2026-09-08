package org.helioviewer.jhv.gui.component;

import java.awt.Dimension;

import javax.swing.JCheckBox;
import javax.swing.JPanel;

/**
 * Neither sidebar may start scrolling sideways while it still has room to compress.
 *
 * <p>The fault this rules out is believing the contents' minimum widths. A Swing control's minimum
 * is its whole label: measured on this codebase, a checkbox reading "Show the residual (what was
 * removed)" will not go below 271 px and a row of such controls adds up, so the camera palette
 * claims a minimum of 392 px and the grid 351, when either would sit happily in half that. A
 * sidebar that honours those numbers puts a horizontal scrollbar in front of the user long before
 * anything has actually run out of room, which is what Gilly reported.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.SidebarSqueezeCheck
 */
public final class SidebarSqueezeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        // A panel that behaves like the real offenders: a control whose minimum is its own label.
        JPanel wide = new JPanel();
        JCheckBox verbose = new JCheckBox("Show the residual (what was removed)");
        wide.add(verbose);
        int claimed = wide.getMinimumSize().width;
        expect("the sample content claims a minimum width worth arguing with (" + claimed + " px)", claimed > 200);

        SqueezeView view = new SqueezeView(wide);
        expect("the view refuses to pass that minimum upward", view.getMinimumSize().width == 0);
        expect("so a sidebar narrower than the content is still a legal size", view.getMinimumSize().width < claimed);

        expect("it always lays out at the viewport's width, so no horizontal scrollbar can appear",
                view.getScrollableTracksViewportWidth());
        expect("but it does not track height, so the stack can still scroll vertically",
                !view.getScrollableTracksViewportHeight());

        // The width the contents are actually given, which is the sidebar's, whatever they asked for.
        expect("a wide sidebar gives its width", SqueezeView.contentWidth(500) == 500);
        expect("a narrow one gives its width too, rather than the content's claim",
                SqueezeView.contentWidth(180) == 180);
        expect("a viewport with no width yet does not go negative", SqueezeView.contentWidth(-10) == 0);

        // The height is the content's own, which is what makes vertical scrolling still work.
        Dimension preferred = view.getPreferredScrollableViewportSize();
        expect("it reports the content's own height", preferred.height == view.getPreferredSize().height);

        System.out.println(failures == 0 ? "SidebarSqueezeCheck: PASS" : "SidebarSqueezeCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private SidebarSqueezeCheck() {}

}
