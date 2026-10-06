package org.helioviewer.jhv.gui;

import java.nio.file.Files;

import org.helioviewer.jhv.app.Settings;

/**
 * What presentation mode leaves on screen, and the rule that a second display overrides all of it.
 *
 * <p>On one screen the picture is the whole display, so anything kept is drawn over the slide:
 * that is a trade the presenter makes knowingly, and it is what these settings are for. On two,
 * nothing is hidden in the first place: the main window stays on the presenter's screen as it is
 * and the projector shows a mirror of its picture.
 *
 * <p>Worth a check rather than a comment because the settings are persisted and the rule is
 * invisible: someone reading keepFor's callers sees two booleans going in and three coming out,
 * and nothing on screen says why a setting they ticked did nothing on the day they had a projector
 * attached.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.gui.PresentationKeepCheck
 */
public final class PresentationKeepCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        // These are written through Settings, so run against a throwaway home.
        System.setProperty("user.home", Files.createTempDirectory("hfs-presentation-keep").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();

        // Nothing recorded yet: the defaults are what a first-time presenter gets.
        PresentationMode.Keep fresh = PresentationMode.keepFor(false);
        expect("out of the box neither sidebar is kept over the slide", !fresh.left() && !fresh.right());
        expect("but floating palettes are, because hiding a window the user opened is the surprise",
                fresh.palettes());

        PresentationMode.setFlag(PresentationMode.KEEP_LEFT, true);
        PresentationMode.setFlag(PresentationMode.KEEP_RIGHT, true);
        PresentationMode.setFlag(PresentationMode.KEEP_PALETTES, false);

        PresentationMode.Keep single = PresentationMode.keepFor(false);
        expect("on one screen the left sidebar setting is honoured", single.left());
        expect("so is the right one", single.right());
        expect("and so is hiding the floating palettes", !single.palettes());

        // On a second screen nothing is hidden: the main window stays on the presenter's screen
        // and the projector shows a mirror (Gilly, 2026-10-06), so every setting is overridden
        // to "keep", including the one turned off above.
        PresentationMode.Keep dual = PresentationMode.keepFor(true);
        expect("on two screens the left sidebar stays, whatever the setting", dual.left());
        expect("as does the right one", dual.right());
        expect("and the palettes, even with the setting turned off", dual.palettes());

        // A hand-edited or half-written settings file must not decide this by accident.
        Settings.setProperty(PresentationMode.KEEP_LEFT, "");
        expect("a blank value falls back to the default rather than parsing as false",
                !PresentationMode.flag(PresentationMode.KEEP_LEFT, false));
        expect("and a blank value with a true default stays true",
                PresentationMode.flag(PresentationMode.KEEP_LEFT, true));
        Settings.setProperty(PresentationMode.KEEP_LEFT, "yes please");
        expect("anything unparseable reads as false, which is the safe side for drawing over a slide",
                !PresentationMode.flag(PresentationMode.KEEP_LEFT, true));

        System.out.println(failures == 0 ? "PresentationKeepCheck: PASS" : "PresentationKeepCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PresentationKeepCheck() {}

}
