package org.helioviewer.jhv.gui.component;

import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * A palette in the sidebar is showing, and has no window. Both halves are true at once, and
 * confusing them crashed the app on launch.
 *
 * <p>When palettes gained a sidebar to live in, isOpen() was widened to mean "showing somewhere",
 * which is what the toolbar and the layer rows want to know. But dock(), which stacks each open
 * palette below the last, had always been entitled to read isOpen() as "has a window", and went on
 * doing so: restoring a session with one palette docked and another floating dereferenced a null
 * dialog inside the stacking loop, on the event thread, during startup.
 *
 * <p>So the two questions are separate methods now, and this pins the difference. If hasWindow
 * ever starts agreeing with isOpen for a sidebar palette, every positional caller is wrong again.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.component.PaletteWindowlessCheck
 */
public final class PaletteWindowlessCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        Palette palette = new Palette("Squeeze test", JPanel::new, () -> {});
        palette.bind(new JToggleButton());

        expect("a palette that was never opened is not showing", !palette.isOpen());
        expect("and has no window", !palette.hasWindow());

        palette.setInSidebar(true);
        expect("docked into the sidebar it counts as showing", palette.isOpen());
        expect("but it still has NO window, which is what dock() must test",
                !palette.hasWindow());
        expect("and it knows where it lives", palette.isInSidebar());

        // Deliberately not floated back out here: that builds a real JDialog, which needs a
        // display and would leave the AWT thread holding the JVM open after main returns. The
        // direction that matters for the crash is this one, into the sidebar.

        System.out.println(failures == 0 ? "PaletteWindowlessCheck: PASS" : "PaletteWindowlessCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PaletteWindowlessCheck() {}

}
