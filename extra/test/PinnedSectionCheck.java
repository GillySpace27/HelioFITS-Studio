package org.helioviewer.jhv.gui.component;

import java.awt.event.ActionEvent;

import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * A section holding the controls for whatever is selected above it does not fold away.
 *
 * <p>Layer options and Overlay options are not asides. Folded, they left a layer selected with no
 * way to adjust it and nothing on screen explaining the absence, and the fold state was
 * remembered, so it stayed that way across launches. If the options are in the way, the section
 * they belong to closes and takes them with it.
 *
 * <p>Three ways in and all of them have to be shut: the chevron (there isn't one), a click on the
 * header, and setExpanded(false) from code. The last matters because PresentationMode walks the
 * whole tree calling setExpanded, and a future caller could just as easily pass false.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.gui.component.PinnedSectionCheck
 */
public final class PinnedSectionCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        JPanel body = new JPanel();
        body.add(new JLabel("controls"));

        // An ordinary section still folds: this is a pin, not a change to every header.
        CollapsiblePane ordinary = new CollapsiblePane("Ordinary", body, true, true);
        ordinary.setExpanded(false);
        expect("an unpinned section still closes", !body.isVisible());

        JPanel pinnedBody = new JPanel();
        pinnedBody.add(new JLabel("controls"));
        CollapsiblePane pinned = new CollapsiblePane("Layer options", pinnedBody, true, true);
        pinned.pinOpen();
        expect("a pinned section starts open", pinnedBody.isVisible());

        pinned.setExpanded(false);
        expect("and refuses to be closed from code", pinnedBody.isVisible());

        // What a click on the header would do, without needing a display to click on.
        pinned.actionPerformed(new ActionEvent(pinned, ActionEvent.ACTION_PERFORMED, "toggle"));
        expect("and ignores a click on its header", pinnedBody.isVisible());

        // Pinning a section that was last left closed has to open it, not honour the memory.
        JPanel rememberedClosed = new JPanel();
        rememberedClosed.add(new JLabel("controls"));
        CollapsiblePane reopened = new CollapsiblePane("Overlay options", rememberedClosed, false, true);
        expect("a section built closed is closed", !rememberedClosed.isVisible());
        reopened.pinOpen();
        expect("and pinning opens it regardless of what was remembered", rememberedClosed.isVisible());

        if (failures != 0)
            throw new AssertionError(failures + " pinned section failure(s)");
        System.out.println("PinnedSectionCheck: PASS");
    }

}
