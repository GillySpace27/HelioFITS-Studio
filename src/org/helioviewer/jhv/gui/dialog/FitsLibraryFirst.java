package org.helioviewer.jhv.gui.dialog;

import java.awt.Component;
import java.io.File;
import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import org.helioviewer.jhv.app.Commands;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.FitsLibrary;

/**
 * Before a FITS download, look in the FITS library for the same dataset over the same range and
 * offer the copy already on disk. Downloading anyway stays one click away: the library may hold a
 * different product or version than the archive serves, and only the person can tell.
 *
 * <p>Helioviewer's JPEG 2000 is not checked: HelioFITS Core keeps FITS, a different product from the
 * streamed browse image, so there is nothing of the same kind to offer.
 */
public final class FitsLibraryFirst {

    private FitsLibraryFirst() {}

    /** What the caller does next. */
    public enum Choice {
        /** Loaded from the library: the add is done. */
        LOADED,
        /** Go on and download, as before. Also the answer with no library or nothing matching. */
        DOWNLOAD,
        /** The person cancelled: leave everything as it is. */
        CANCELLED
    }

    /**
     * No library, nothing matching in the range, or a library that cannot be read all answer
     * DOWNLOAD, so a download is never blocked by HelioFITS Core.
     */
    public static Choice offer(Component parent, FitsLibrary.Wanted wanted, long start, long end) {
        FitsLibrary.Location lib = FitsLibrary.find();
        if (lib == null)
            return Choice.DOWNLOAD;
        List<FitsLibrary.Dataset> hits = new ArrayList<>();
        try {
            // ponytail: read on the event thread; the index answers in milliseconds for libraries of
            // tens of thousands of files. Move to a worker if a large library makes Add stutter.
            for (FitsLibrary.Dataset set : FitsLibrary.datasets(lib, start, end))
                if (set.inRange() > 0 && wanted.matches(set.key()))
                    hits.add(set);
        } catch (SQLException | FitsLibrary.TooNew e) {
            Log.warn("FITS library not checked before download: " + e.getMessage());
            return Choice.DOWNLOAD;
        }
        if (hits.isEmpty())
            return Choice.DOWNLOAD;

        JList<String> list = new JList<>(hits.stream()
                .map(h -> h.label() + "  (" + String.format("%,d", h.inRange()) + " in range)").toArray(String[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setVisibleRowCount(Math.min(hits.size(), 6));
        JPanel panel = new JPanel(new java.awt.BorderLayout(0, 6));
        panel.add(new JLabel("<html>The FITS library already has this in the time range, on this computer.<br>"
                + "Load its copy, or download from the archive anyway?</html>"), java.awt.BorderLayout.PAGE_START);
        panel.add(new JScrollPane(list), java.awt.BorderLayout.CENTER);

        String[] options = {"Use the Library's Copy", "Download Anyway", "Cancel"};
        int choice = JOptionPane.showOptionDialog(parent, panel, "Already in the FITS Library",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == 1)
            return Choice.DOWNLOAD;
        if (choice != 0)
            return Choice.CANCELLED; // or the window closed
        FitsLibrary.Dataset set = hits.get(Math.max(0, list.getSelectedIndex()));
        try {
            List<URI> uris = new ArrayList<>();
            for (File file : FitsLibrary.files(lib, set.key(), start, end))
                if (file.isFile()) // a delete in HelioFITS Core since the index was read
                    uris.add(file.toURI());
            if (uris.isEmpty()) {
                Message.warn("Nothing left to load", "The files of " + set.label() + " are no longer in the FITS library.");
                return Choice.CANCELLED;
            }
            Commands.loadImage(uris);
            return Choice.LOADED;
        } catch (SQLException | FitsLibrary.TooNew e) {
            Message.err("Could not read the FITS library", e.getMessage(), e);
            return Choice.CANCELLED;
        }
    }

}
