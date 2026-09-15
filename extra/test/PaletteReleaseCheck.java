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
 * <p>"Put away" then changed meaning and this check did not, which is the second thing it is now
 * good for. It used to take the section out of the sidebar entirely; since Palette.bind sends a
 * docked toggle through SectionHost.revealOrFold, it folds the section shut and leaves it in
 * place, so a button press cannot make a panel vanish from a sidebar the user is looking at. The
 * assertions below moved to the fold contract on 2026-09-12, two commits after the behaviour did.
 * They had been failing in between, and nothing said so because until `ant test` there was no way
 * to run the checks except one at a time by hand.
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

    public static void main(String[] args) throws java.io.IOException {
        // Before the first mention of Settings: a palette records where it lives, so without this
        // the check writes ui.palette.* keys for a palette that does not exist into the settings
        // file of the application it is checking. Same isolation StartupAppearanceCheck uses.
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-palette-release").toString());
        String title = "Release test";
        Palette palette = new Palette(title, JPanel::new, () -> {});
        JToggleButton button = new JToggleButton();
        palette.bind(button);

        palette.setHome(RightSidebar.getInstance());
        expect("docked, it is showing", palette.isOpen());
        expect("its toolbar button says so", button.isSelected());
        expect("exactly one section, not two", sections(title) == 1);

        RightSidebar bar = RightSidebar.getInstance();
        expect("and it is unfolded to start with", bar.isUnfolded(title));

        button.doClick(); // the fold
        expect("folded, it is shut", !bar.isUnfolded(title));
        expect("the button came up with it", !button.isSelected());
        // The point of the change: a toolbar button folds a docked palette, it does not take it
        // out of the sidebar. Whipping a panel out from under the user was the thing to stop.
        expect("but the section stays in the sidebar", sections(title) == 1);
        expect("so it still lives there, not in a window", palette.isDocked());
        expect("and it grew no window on the way", !palette.hasWindow());

        button.doClick(); // and back
        expect("clicked again it is unfolded once more", bar.isUnfolded(title));
        expect("with its button lit", button.isSelected());
        expect("still exactly one section: no ghost left by the fold", sections(title) == 1);
        expect("still no window", !palette.hasWindow());

        // Not popped out here: that builds a real JDialog, which needs a display and would leave
        // the AWT thread holding the JVM open after main returns. See PaletteWindowlessCheck.

        System.out.println(failures == 0 ? "PaletteReleaseCheck: PASS" : "PaletteReleaseCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PaletteReleaseCheck() {}

}
