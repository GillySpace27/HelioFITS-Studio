package org.helioviewer.jhv.gui;

import org.helioviewer.jhv.app.Session;

/**
 * The unsaved-changes asterisk, and the one thing it must never touch.
 *
 * <p>The mark goes on the DISPLAYED name only. Session.displayName stays the plain name, because
 * two other things read it: the rename field seeds from it, and suggestedSaveName turns it into a
 * filename. An asterisk reaching either would mean renaming a session to "*Untitled" or writing a
 * file called "*whatever.jhv", which is the failure this check exists to prevent.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.SessionDirtyMarkCheck
 */
public final class SessionDirtyMarkCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        expect("a clean session shows its name alone",
                "Corona run".equals(MainFrame.displayedSessionName("Corona run", false)));
        expect("an unsaved one is prefixed with an asterisk",
                "*Corona run".equals(MainFrame.displayedSessionName("Corona run", true)));
        expect("a nameless session is Untitled", "Untitled".equals(MainFrame.displayedSessionName(null, false)));
        expect("a blank name is Untitled too", "Untitled".equals(MainFrame.displayedSessionName("   ", false)));
        expect("and an unsaved untitled one carries the mark",
                "*Untitled".equals(MainFrame.displayedSessionName(null, true)));
        expect("the mark leads, so names still sort and read normally",
                MainFrame.displayedSessionName("x", true).indexOf('*') == 0);

        // The source the mark reads, and the name it must not reach.
        Session.markSaved();
        expect("a saved session is not dirty", !Session.isDirty());
        expect("and its name is unmarked", !Session.displayName().contains("*"));
        String saveName = Session.suggestedSaveName();
        Session.markDirty();
        expect("a change makes it dirty", Session.isDirty());
        expect("but the name itself never gains the mark", !Session.displayName().contains("*"));
        expect("so the filename it suggests is unchanged", saveName.equals(Session.suggestedSaveName()));
        expect("and no suggested filename could contain one", !Session.suggestedSaveName().contains("*"));
        Session.markSaved();
        expect("saving clears it again", !Session.isDirty());

        System.out.println(failures == 0 ? "SessionDirtyMarkCheck: PASS" : "SessionDirtyMarkCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private SessionDirtyMarkCheck() {}

}
