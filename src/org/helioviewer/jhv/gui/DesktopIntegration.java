package org.helioviewer.jhv.gui;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.desktop.AboutHandler;
import java.awt.desktop.PreferencesHandler;
import java.awt.desktop.QuitHandler;
import java.io.File;
import java.net.URI;

import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;

import org.helioviewer.jhv.app.Log;

public final class DesktopIntegration {

    public static final boolean canBrowse = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE);
    public static final boolean canOpen = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN);
    // BROWSE_FILE_DIR selects the file inside its folder (Finder on macOS, the file manager on
    // Linux). Windows does not support it, so there we settle for opening the folder itself.
    public static final boolean canRevealFile = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR);
    public static final int menuShortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    /** Show a file in the desktop's file manager, selected where the platform allows it. */
    public static void reveal(File file) {
        try {
            if (!file.isDirectory() && canRevealFile)
                Desktop.getDesktop().browseFileDirectory(file);
            else if (canOpen)
                Desktop.getDesktop().open(file.isDirectory() ? file : file.getParentFile());
        } catch (Exception e) {
            Log.warn("Could not reveal " + file, e);
        }
    }

    public static final HyperlinkListener hyperOpenURL = e -> {
        if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null)
            openURL(e.getURL().toString());
    };

    public static void openURL(String url) {
        try {
            if (url == null)
                return;

            openURI(new URI(url));
        } catch (Exception e) {
            Log.warn(e);
        }
    }

    private static void openURI(URI uri) throws Exception {
        if ("file".equalsIgnoreCase(uri.getScheme()) && canOpen)
            Desktop.getDesktop().open(new File(uri));
        else if (canBrowse)
            Desktop.getDesktop().browse(uri);
    }

    public static void setQuitHandler(QuitHandler handler) {
        Desktop.getDesktop().setQuitHandler(handler);
    }

    public static void setPreferencesHandler(PreferencesHandler handler) {
        Desktop.getDesktop().setPreferencesHandler(handler);
    }

    public static void setAboutHandler(AboutHandler handler) {
        Desktop.getDesktop().setAboutHandler(handler);
    }

    private DesktopIntegration() {}
}
