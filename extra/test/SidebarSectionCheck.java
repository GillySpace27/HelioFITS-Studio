package org.helioviewer.jhv.gui.component;

import javax.swing.JPanel;

/**
 * A pane popped out of the left sidebar goes back where it came from, and a palette knows which
 * sidebar it lives in.
 *
 * <p>Two things are pinned here. The first is position. The left sidebar's pane is SHARED: the
 * plugins add their own sections to it (Timeline Layers, the Space Weather Event Knowledgebase),
 * so popping a pane out and back cannot rebuild the stack, and re-adding at the end would silently
 * reorder the sidebar every time somebody used the feature. It is an insert at a remembered index
 * instead, and this is the arithmetic behind that.
 *
 * <p>The second is the stored home. Where a palette lives is persisted by name, so those names are
 * API. "true" among them: it is what the build that had only one sidebar wrote, and those settings
 * files are already on disk, so it has to keep meaning the right sidebar.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.gui.component.SidebarSectionCheck
 */
public final class SidebarSectionCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        SideContentPane pane = new SideContentPane();
        JPanel a = new JPanel(), b = new JPanel(), c = new JPanel(), d = new JPanel();
        pane.add("A", a, true);
        pane.add("B", b, true);
        pane.add("C", c, true);
        expect("sections stack in the order they were added",
                pane.indexOf(a) == 0 && pane.indexOf(b) == 1 && pane.indexOf(c) == 2);

        int was = pane.indexOf(b);
        pane.remove(b);
        expect("popping one out leaves the others in order",
                pane.indexOf(a) == 0 && pane.indexOf(c) == 1);
        expect("and it is genuinely gone", pane.indexOf(b) < 0);

        pane.add("B", b, true, null, "B", LeftSidebar.insertIndex(was, pane.getComponentCount()));
        expect("popping it back in puts it between A and C, not on the end",
                pane.indexOf(a) == 0 && pane.indexOf(b) == 1 && pane.indexOf(c) == 2);

        pane.add("D", d, true, null, "D", LeftSidebar.insertIndex(null, pane.getComponentCount()));
        expect("a section that has never been here goes on the end", pane.indexOf(d) == 3);

        expect("a remembered index inside the pane is used as it stands",
                LeftSidebar.insertIndex(2, 4) == 2);
        expect("one past the end appends rather than throwing",
                LeftSidebar.insertIndex(99, 4) == -1);
        expect("and never having been here appends too",
                LeftSidebar.insertIndex(null, 4) == -1);

        expect("\"left\" is the left sidebar", Palette.hostNamed("left") == LeftSidebar.getInstance());
        expect("\"right\" is the right one", Palette.hostNamed("right") == RightSidebar.getInstance());
        expect("\"true\" is the right one too: it is what the one-sidebar build wrote",
                Palette.hostNamed("true") == RightSidebar.getInstance());
        expect("a window is not a sidebar", Palette.hostNamed("window") == null);
        expect("and neither is nothing stored at all", Palette.hostNamed(null) == null);

        System.out.println(failures == 0 ? "SidebarSectionCheck: PASS" : "SidebarSectionCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private SidebarSectionCheck() {}

}
