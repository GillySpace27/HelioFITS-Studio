package org.helioviewer.jhv.io;

import java.io.File;
import java.net.URI;
import java.util.Arrays;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Session;

/**
 * A session opened from the desktop is the session the window opens.
 *
 * <p>macOS hands a file double-clicked in Finder to the app as an open-documents event, not as an
 * argument, and with no handler the event was dropped: the launch looked bare, the Welcome window
 * came up over the last session, and the clicked file never loaded (Gilly, 2026-10-07). Windows and
 * Linux pass the file as a bare argument, which no option claimed. This pins both routes: a bare
 * .jhv argument becomes -state, and a desktop document that arrives before the startup session is
 * opened replaces it, while one that arrives after is left to the caller to open directly.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.io.DesktopDocumentCheck
 */
public final class DesktopDocumentCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static void promote(String[] in, String[] want) {
        String[] got = CommandLine.promoteSessionArgument(in);
        expect(Arrays.toString(in) + " -> " + Arrays.toString(want) + ", got " + Arrays.toString(got), Arrays.equals(got, want));
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-desktop-doc").toString());
        Platform.init();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs(); // else every Settings write logs a stack trace over the results
        Directories.STATES.getFile().mkdirs();

        promote(new String[] {}, new String[] {});
        promote(new String[] {"/a/b.jhv"}, new String[] {"-state", "/a/b.jhv"});
        promote(new String[] {"C:\\Data\\Talk.JHV"}, new String[] {"-state", "C:\\Data\\Talk.JHV"});
        promote(new String[] {"-load", "x.jhv"}, new String[] {"-load", "x.jhv"});
        promote(new String[] {"-state", "x.jhv"}, new String[] {"-state", "x.jhv"});
        promote(new String[] {"image.fits"}, new String[] {"image.fits"});

        File states = Directories.STATES.getFile();
        File autosave = new File(states, "Last.jhv");
        File clicked = new File(states, "Clicked.jhv");
        File late = new File(states, "Late.jhv");
        for (File f : new File[] {autosave, clicked, late})
            java.nio.file.Files.writeString(f.toPath(), "{}"); // readable, so -state resolves each one

        // Startup would reopen the autosave; the desktop asks for Clicked.jhv before that happens.
        CommandLine.setArguments(new String[] {"-state", autosave.getPath()});
        expect("no desktop document yet", !CommandLine.desktopDocumentRequested());
        expect("a document before the startup session folds into startup", CommandLine.foldIntoStartup(clicked.toURI()));
        expect("and stands down the Welcome window", CommandLine.desktopDocumentRequested());
        URI[] loaded = new URI[1];
        URI opened = CommandLine.openStateArgument(u -> loaded[0] = u);
        expect("startup opens the clicked file, not the autosave, got " + loaded[0],
                clicked.toURI().equals(loaded[0]) && clicked.toURI().equals(opened));
        expect("and the window then belongs to it, got " + Session.currentSessionFile(),
                clicked.getAbsoluteFile().equals(Session.currentSessionFile()));

        // Once startup has opened its session, a later desktop open is the caller's to load.
        expect("a document after startup is not folded in", !CommandLine.foldIntoStartup(late.toURI()));

        System.out.println(failures == 0 ? "DesktopDocumentCheck: all ok" : "DesktopDocumentCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

}
