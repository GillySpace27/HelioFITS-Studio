package org.helioviewer.jhv.gui.component;

import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * A docked palette can be put away and brought back with its toolbar button, and stays docked
 * through both. Popping it out is a separate act.
 *
 * <p>These were one thing, and the result was a palette you could put into the right sidebar and
 * never get rid of: living there counted as showing, so the toolbar button was permanently lit and
 * clicking it only scrolled the section into view. Gilly's words: the icons at the top had no way
 * of being released.
 *
 * <p>Also pins the sidebar's own half of it. removeSection used to drop the section from its map
 * and then rebuild from that map, so the section it had just dropped was never taken out of the
 * pane: the header stayed behind with its content stolen by the new window, and docking the same
 * palette again put a second header beside the first. Hence the count assertions.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.PaletteReleaseCheck
 */
public final class PaletteReleaseCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static long sections(String title) {
        return RightSidebar.getInstance().sectionTitles().stream().filter(title::equals).count();
    }

    public static void main(String[] args) {
        String title = "Release test";
        Palette palette = new Palette(title, JPanel::new, () -> {});
        JToggleButton button = new JToggleButton();
        palette.bind(button);

        palette.setInSidebar(true);
        expect("docked, it is showing", palette.isOpen());
        expect("its toolbar button says so", button.isSelected());
        expect("exactly one section, not two", sections(title) == 1);

        button.doClick(); // the release
        expect("released, it is not showing", !palette.isOpen());
        expect("the button came up with it", !button.isSelected());
        expect("and the section is gone from the sidebar", sections(title) == 0);
        expect("but it still lives in the sidebar, not in a window", palette.isInSidebar());
        expect("and it grew no window on the way", !palette.hasWindow());

        button.doClick(); // and back
        expect("clicked again it is showing once more", palette.isOpen());
        expect("with its button lit", button.isSelected());
        expect("still exactly one section: no ghost left by the release", sections(title) == 1);
        expect("still no window", !palette.hasWindow());

        // Not popped out here: that builds a real JDialog, which needs a display and would leave
        // the AWT thread holding the JVM open after main returns. See PaletteWindowlessCheck.

        System.out.println(failures == 0 ? "PaletteReleaseCheck: PASS" : "PaletteReleaseCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PaletteReleaseCheck() {}

}
