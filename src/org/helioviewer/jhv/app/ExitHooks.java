package org.helioviewer.jhv.app;

import org.helioviewer.jhv.movie.ExportMovie;

public class ExitHooks {

    private static final Thread finishMovieThread = new Thread(() -> {
        try {
            ExportMovie.disposeMovieWriter(false);
        } catch (Exception e) {
            Log.warn("Movie was not shut down properly");
        }
    }, "JHV-FinishMovie");

    public static void attach() {
        // At the moment this runs, the EventQueue is blocked (by enforcing to run System.exit on it which is blocking)
        Runtime.getRuntime().addShutdownHook(finishMovieThread);
    }

    // Returns false to abort the quit (unsaved-changes "Cancel"), which blocks an OS shutdown.
    // closingThisWindow: true for the red close button (dismiss this window, don't reopen it),
    // false for Cmd-Q / app quit (keep every window in the reopen set).
    public static boolean exitProgram(boolean closingThisWindow) {
        return Session.confirmExitAndAutosave(closingThisWindow);
    }

    public static boolean exitProgram() {
        return exitProgram(false);
    }

}
