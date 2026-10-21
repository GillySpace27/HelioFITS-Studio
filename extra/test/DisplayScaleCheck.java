package org.helioviewer.jhv.gui;

/**
 * View > Display Settings reads its two factors safely: absent, unreadable or absurd values give
 * 100%, so a hand-edited or corrupt settings file cannot launch the app at 0% or 4000% scale.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.DisplayScaleCheck
 */
public final class DisplayScaleCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        expect("absent reads as 100%", DisplayScale.read(null) == 1);
        expect("a stored 1.5 reads as 150%", DisplayScale.read("1.5") == 1.5);
        expect("text that is not a number reads as 100%", DisplayScale.read("big") == 1);
        expect("zero reads as 100%", DisplayScale.read("0") == 1);
        expect("forty times reads as 100%", DisplayScale.read("40") == 1);
        System.out.println(failures == 0 ? "DisplayScaleCheck: ok" : "DisplayScaleCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
