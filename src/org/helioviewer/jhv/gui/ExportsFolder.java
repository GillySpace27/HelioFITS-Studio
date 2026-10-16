package org.helioviewer.jhv.gui;

import java.awt.event.ActionEvent;
import java.io.File;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.Action;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.io.Directories;

/**
 * The folder every export is written to, opened in the desktop's file manager on request (issue #12).
 *
 * <p>The folder is created at startup, but it can be deleted while the app runs, and opening a
 * missing folder fails silently on some platforms; so it is made again first.
 */
public final class ExportsFolder {

    /** What a button that shows a file in the file manager says on this platform, as the cache dialog has it. */
    public static final String SHOW_LABEL = Platform.isMacOS() ? "Show in Finder" : "Show in Folder";

    /** The exports folder, created when missing; null when it cannot be created. */
    @Nullable
    public static File ensure() {
        File dir = Directories.EXPORTS.getFile();
        return dir.isDirectory() || dir.mkdirs() ? dir : null;
    }

    /** Open the exports folder in the file manager, saying so when that is impossible. */
    public static void open() {
        File dir = ensure();
        if (dir == null) {
            Log.warn("Could not create the exports folder " + Directories.EXPORTS.getPath());
            Message.warn("Exports folder unavailable", "Could not create " + Directories.EXPORTS.getPath());
        } else if (!DesktopIntegration.canOpen) {
            Message.info("Exports folder", "Exports are saved in " + dir.getPath());
        } else
            DesktopIntegration.reveal(dir);
    }

    /** Show one exported file, selected where the platform allows it, or the exports folder when there is none. */
    public static void reveal(@Nullable String path) {
        File f = path == null ? null : new File(path);
        if (f != null && f.exists())
            DesktopIntegration.reveal(f);
        else
            open();
    }

    public static Action action() {
        return new AbstractAction("Open Exports Folder") {
            @Override
            public void actionPerformed(ActionEvent e) {
                open();
            }
        };
    }

    private ExportsFolder() {}
}
