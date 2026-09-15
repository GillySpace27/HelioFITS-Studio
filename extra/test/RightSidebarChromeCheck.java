package org.helioviewer.jhv.gui.component;

import javax.swing.JPanel;

/**
 * The right sidebar comes back from presentation mode, and its toolbar button never lies about it.
 *
 * <p>Two owners write the visibility of the one component: RightSidebar.rebuild sets it from
 * whether anything is docked, and MainFrame.setChromeVisible sets it from what presentation mode
 * saw on the way in. The second one wrote it directly, told the Timelines button about it and not
 * the right-bar button, so leaving presentation mode could leave the bar with no size at all while
 * the button stayed lit and the collapsed flag stayed false. Gilly's words: the bar itself is
 * absent. The only way back was to collapse and uncollapse, because setCollapsed is the one thing
 * that re-applies the layout, and it short-circuits when the flag has not changed.
 *
 * <p>So the invariant is not "setChromeVisible must be careful". It is that the sidebar can be
 * asked to restate itself, and that restating it is what the chrome restore does. Anything that
 * hid the bar behind its back is then undone by construction, including paths not found yet.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.RightSidebarChromeCheck
 */
public final class RightSidebarChromeCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws java.io.IOException {
        // As PaletteReleaseCheck: a palette records where it lives, so keep this out of the
        // settings file of the application being checked.
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-right-chrome").toString());

        RightSidebar bar = RightSidebar.getInstance();
        String title = "Chrome test";
        bar.addSection(title, null, new JPanel(), () -> {});
        bar.setCollapsed(false);

        expect("docked, the bar has size", bar.component().isVisible());

        // What MainFrame.setChromeVisible does on the way into presentation mode: it writes this
        // component directly, which is the whole problem.
        bar.component().setVisible(false);
        expect("presentation took it away", !bar.component().isVisible());

        // And what leaving presentation mode must do. Not setCollapsed(false): the flag never
        // changed, so that short-circuits and the bar stays gone, which is the bug.
        bar.setCollapsed(false);
        expect("setCollapsed alone does not bring it back, so it cannot be the restore",
                !bar.component().isVisible());

        bar.refresh();
        expect("restated, the bar is back", bar.component().isVisible());
        expect("and it is not collapsed, because it was not", !bar.isCollapsed());

        // An empty bar must stay away: refresh restates what is true, it does not force a rail
        // onto the edge of a window with nothing in it.
        bar.removeSection(title);
        bar.refresh();
        expect("nothing docked, nothing shown", !bar.component().isVisible());

        System.out.println(failures == 0 ? "RightSidebarChromeCheck: PASS" : "RightSidebarChromeCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
