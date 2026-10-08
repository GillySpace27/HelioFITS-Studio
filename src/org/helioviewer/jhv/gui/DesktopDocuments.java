package org.helioviewer.jhv.gui;

import java.awt.Desktop;
import java.awt.EventQueue;
import java.io.File;
import java.util.List;
import java.util.Locale;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Session;
import org.helioviewer.jhv.gui.dialog.WelcomeDialog;
import org.helioviewer.jhv.io.CommandLine;

/**
 * Session files the desktop hands this app: a double-click in Finder, Open With, a drop on the
 * Dock icon. macOS sends these as an open-documents event rather than as arguments, and with no
 * handler installed AWT holds the event forever, so a launch from Finder used to be a bare launch
 * with the Welcome window over the last session and the clicked file never opened.
 *
 * <p>Installed from main, before the window exists: AWT queues an event that arrives before a
 * handler is set and delivers it on installation, so the launching file is not lost either way.
 */
public final class DesktopDocuments {

    public static void install() {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE))
            return;
        Desktop.getDesktop().setOpenFileHandler(e -> {
            List<File> files = List.copyOf(e.getFiles());
            EventQueue.invokeLater(() -> open(files));
        });
    }

    private static void open(List<File> files) {
        File session = null;
        for (File f : files) {
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(".jhv")) {
                if (session == null)
                    session = f;
                else
                    Log.warn("One window holds one session; not opening " + f + " as well as " + session);
            } else
                Log.warn("Opened from the desktop but not a session file, not loaded: " + f);
        }
        if (session == null)
            return;
        Log.info("Session opened from the desktop: " + session);
        WelcomeDialog.dismiss(); // the user has said what to open; the start choices would be in the way
        if (CommandLine.foldIntoStartup(session.toURI()))
            return; // startup will open it in place of the session it would have reopened
        Commands.loadState(session.toURI()); // already running: as File > Open Recent does
        Session.setSessionFile(session, true);
    }

    private DesktopDocuments() {}

}
