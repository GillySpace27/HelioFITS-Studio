package org.helioviewer.jhv.app;

import java.io.File;

import org.helioviewer.jhv.io.Directories;

/**
 * A session named on the command line becomes the session the window is in.
 *
 * <p>Opening a session and autosaving to one were separate decisions, and -state made only the first:
 * the window went on writing to the file it remembered from the previous launch. Loading A and quitting
 * therefore wrote A's scene over B, silently, with B's name nowhere on screen. It cost a real project
 * file on 2026-09-20 before it was noticed, which is why the no-op case is checked too: the ordinary
 * launch appends the window's own file as -state, and repointing there must change nothing.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.SessionStateArgCheck
 */
public final class SessionStateArgCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-session-state").toString());
        Platform.init();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs(); // else every Settings write logs a stack trace over the results
        Directories.STATES.getFile().mkdirs();

        File states = Directories.STATES.getFile();
        File remembered = new File(states, "Remembered.jhv");
        File opened = new File(states, "Opened.jhv");
        File auto = new File(states, "session-main.jhv");

        // Last launch left the window in a named project, which is what it would autosave to.
        Settings.setProperty("session.mainFile", remembered.getAbsolutePath());
        Session.restoreCandidate(); // resolves the window's file, as startup does
        expect("the window starts in the session it remembers, got " + Session.currentSessionFile(),
                remembered.equals(Session.currentSessionFile()));

        // The ordinary launch: the appended -state IS the window's own file.
        Session.adoptSessionFile(remembered);
        expect("adopting the file already open changes nothing", remembered.equals(Session.currentSessionFile()));

        // -state on a different project. This is the case that used to load one file and save another.
        Session.adoptSessionFile(opened);
        expect("-state moves the window into that session, got " + Session.currentSessionFile(),
                opened.equals(Session.currentSessionFile()));
        expect("which is a named project, not Untitled", Session.isNamedSession());
        expect("and the next launch reopens it rather than the old one, got "
                        + Settings.getProperty("session.mainFile"),
                opened.getAbsolutePath().equals(Settings.getProperty("session.mainFile")));

        // The autosave file is not a project: adopting it must not put a name in the title bar.
        Session.adoptSessionFile(auto);
        expect("the window's own autosave file stays Untitled", !Session.isNamedSession());

        System.out.println(failures == 0 ? "SessionStateArgCheck: ok" : "SessionStateArgCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
