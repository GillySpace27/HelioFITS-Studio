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

        // The E28 launch (vault projects/jhelioviewer.md:1110-1161): the window remembers one project
        // and the command line names another. Through the real argument path, the named file must be
        // the one loaded AND the one the window then writes to; before 3ceee8478 it loaded the named
        // file and saved over the remembered one.
        File e28 = new File(states, "E28_ladder.jhv");
        java.nio.file.Files.writeString(e28.toPath(), "{}"); // -state only takes a readable file
        java.nio.file.Files.writeString(remembered.toPath(), "{}"); // so the restore candidate exists too
        Session.setSessionFile(remembered, true); // where the last launch left the window
        org.helioviewer.jhv.io.CommandLine.setArguments(new String[] {"-state", e28.getPath()});
        java.util.List<java.net.URI> loaded = new java.util.ArrayList<>();
        org.helioviewer.jhv.io.CommandLine.openStateArgument(loaded::add);
        expect("E28: the -state file is the one loaded, got " + loaded,
                loaded.size() == 1 && e28.getAbsoluteFile().equals(new File(loaded.get(0))));
        expect("E28: and the window then saves to it, not the remembered one, got " + Session.currentSessionFile(),
                e28.getAbsoluteFile().equals(Session.currentSessionFile()));
        expect("E28: and the next plain launch reopens it, got " + Settings.getProperty("session.mainFile"),
                e28.getAbsolutePath().equals(Settings.getProperty("session.mainFile")));

        System.out.println(failures == 0 ? "SessionStateArgCheck: ok" : "SessionStateArgCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
