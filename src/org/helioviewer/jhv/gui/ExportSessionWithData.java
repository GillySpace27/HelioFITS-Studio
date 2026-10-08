package org.helioviewer.jhv.gui;

import java.awt.FileDialog;
import java.awt.event.ActionEvent;
import java.nio.file.Path;

import javax.swing.AbstractAction;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.app.state.SessionArchive;
import org.helioviewer.jhv.app.state.State;
import org.helioviewer.jhv.io.ExtensionFileFilter;
import org.helioviewer.jhv.thread.Task;

import org.json.JSONObject;

/**
 * File &gt; Export Session with Data: a .jhv plus, beside it, a {@code .data.zip} holding every
 * local file the session reads (see {@link SessionArchive}). Archive layers are not packed: they
 * download again on the other machine, as they always have.
 *
 * <p>An export, not a save: the window goes on autosaving to the session it was in, so the
 * exported pair stays the snapshot that was sent.
 */
public final class ExportSessionWithData extends AbstractAction {

    public ExportSessionWithData() {
        super("Export Session with Data...");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        FileDialog fileDialog = new FileDialog(MainFrame.get(), "Export session with data", FileDialog.SAVE);
        fileDialog.setFilenameFilter(ExtensionFileFilter.JHV);
        fileDialog.setMultipleMode(false);
        fileDialog.setDirectory(Settings.getProperty("path.state"));
        fileDialog.setFile(org.helioviewer.jhv.app.Session.suggestedSaveName() + ".jhv");
        fileDialog.setVisible(true);

        String directory = fileDialog.getDirectory();
        String file = fileDialog.getFile();
        if (directory == null || file == null)
            return;
        if (!file.toLowerCase(java.util.Locale.ROOT).endsWith(".jhv"))
            file += ".jhv";
        Path target = Path.of(directory, file);
        JSONObject snapshot = State.snapshot(); // on the EDT, as every save takes it
        Task.submitBackground(() -> SessionArchive.export(snapshot, target), ExportSessionWithData::done,
                t -> {
                    Log.error("Export session with data", t);
                    Message.err("Export failed", "Nothing was replaced. " + t.getMessage(), t);
                });
    }

    private static void done(SessionArchive.Export r) {
        if (r.archive() == null)
            Message.info("Session exported", "Saved " + r.session().getFileName()
                    + ". No layer reads a local file, so no data archive was needed: the archive layers download again wherever it is opened.");
        else
            Message.info("Session exported", "Saved " + r.session().getFileName() + " and " + r.archive().getFileName()
                    + " (" + r.files() + " local file(s), " + humanBytes(r.bytes()) + "). Keep the two together;"
                    + " opening the .jhv on another computer unpacks the data automatically.");
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[u]);
    }
}
