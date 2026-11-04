package org.helioviewer.jhv.gui.dialog;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;

import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.io.FitsCoreIngest;
import org.helioviewer.jhv.io.FitsLibrary;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Cache Manager window's FITS library tools: Import Folder..., Verify and Delete... on the
 * FITS Library tab. Studio only reads the library; every change goes through HelioFITS Core
 * ({@code ingest.tree}, {@code library.verify}, {@code library.delete}), the library's one writer.
 * Without a Core (Linux and Windows packages carry none yet, nor does a build tree unless
 * HFCORE_CORE is set) the buttons stay greyed out and say why.
 *
 * <p>Delete always asks Core for a dry run first and shows what it would remove before anything
 * goes. The advanced tools (archive search, cadence gaps, reindex, views) stay in pyHelioFITS.
 */
final class LibraryTools {

    private static final Duration QUICK = Duration.ofMinutes(2);
    private static final Duration LONG = Duration.ofHours(2); // a checksum of every file, or a large folder
    private static final String NO_CORE = "Needs HelioFITS Core: carried by the macOS app; elsewhere set HFCORE_CORE";

    private LibraryTools() {
    }

    static void install(FitsLibraryTab tab, Component parent) {
        JButton importFolder = new JButton("Import Folder...");
        importFolder.addActionListener(e -> importFolder(tab, parent));
        JButton verify = new JButton("Verify");
        verify.addActionListener(e -> verify(tab, parent));
        // Re-judged each time the tab is shown, since a library or a Core can appear while Studio runs
        tab.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                judge(tab, importFolder, "Copy every FITS file under a folder into the library, each kept once by checksum");
                judge(tab, verify, "Recompute every file's checksum and list missing or altered files; changes nothing");
            }
        });
        judge(tab, importFolder, "Copy every FITS file under a folder into the library, each kept once by checksum");
        judge(tab, verify, "Recompute every file's checksum and list missing or altered files; changes nothing");
        tab.addTool(verify);
        tab.addTool(importFolder);
    }

    private static void judge(FitsLibraryTab tab, JButton button, String tip) {
        boolean ok = coreFound(tab);
        button.setEnabled(ok);
        button.setToolTipText(ok ? tip : tab.library() == null ? "No FITS library on this computer" : NO_CORE);
    }

    static boolean coreFound(FitsLibraryTab tab) {
        FitsLibrary.Location lib = tab.library();
        return lib != null && FitsCoreIngest.coreFound(lib.root());
    }

    /** The selected dataset's folder, or the library's own folder when nothing is selected. */
    static void reveal(FitsLibraryTab tab) {
        FitsLibrary.Location lib = tab.library();
        if (lib == null)
            return;
        List<FitsLibrary.Dataset> sets = tab.selected();
        if (sets.size() == 1) {
            try {
                List<File> files = FitsLibrary.files(lib, sets.getFirst().key());
                if (!files.isEmpty() && files.getFirst().exists()) {
                    DesktopIntegration.reveal(files.getFirst());
                    return;
                }
            } catch (SQLException | FitsLibrary.TooNew e) {
                Message.err("Could not read the FITS library", e.getMessage(), e);
                return;
            }
        }
        DesktopIntegration.reveal(lib.root().toFile());
    }

    static void importFolder(FitsLibraryTab tab, Component parent) {
        FitsLibrary.Location lib = tab.library();
        if (lib == null)
            return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import a Folder of FITS into the Library");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION)
            return;
        File dir = chooser.getSelectedFile();
        run(parent, "Importing " + dir.getName() + "...", lib.root(), "ingest.tree", new JSONObject().put("path", dir.getPath()), LONG, out -> {
            JSONArray errors = out.optJSONArray("errors");
            int bad = errors == null ? 0 : errors.length();
            JSONObject counts = out.optJSONObject("counts");
            StringBuilder sb = new StringBuilder(String.format("%,d", out.optInt("files")) + " FITS files looked at in " + dir.getName() + ".");
            if (counts != null)
                for (String key : counts.keySet())
                    sb.append("\n").append(key.replace('_', ' ')).append(": ").append(String.format("%,d", counts.optLong(key)));
            if (bad > 0)
                sb.append("\n").append(bad).append(bad == 1 ? " file" : " files").append(" could not be added; see the log.");
            JOptionPane.showMessageDialog(parent, sb.toString(), "Import Folder", bad > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
            tab.refresh();
        });
    }

    static void verify(FitsLibraryTab tab, Component parent) {
        FitsLibrary.Location lib = tab.library();
        if (lib == null)
            return;
        run(parent, "Verifying the FITS library...", lib.root(), "library.verify", new JSONObject(), LONG, out -> {
            JSONArray missing = out.optJSONArray("missing"), altered = out.optJSONArray("altered");
            int m = missing == null ? 0 : missing.length(), a = altered == null ? 0 : altered.length();
            String text = String.format("%,d", out.optInt("ok")) + " files match their checksums.";
            if (m + a == 0)
                text += "\nNothing is missing or altered.";
            else {
                text += "\n" + m + " missing, " + a + " altered. First of them:";
                for (JSONArray list : new JSONArray[]{missing, altered})
                    for (int i = 0; list != null && i < Math.min(5, list.length()); i++)
                        text += "\n  " + list.optString(i);
            }
            JOptionPane.showMessageDialog(parent, text, "Verify", m + a == 0 ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        });
    }

    /** Ask Core what deleting the selected datasets would remove, show it, and only then delete. */
    static void delete(FitsLibraryTab tab, Component parent) {
        FitsLibrary.Location lib = tab.library();
        List<FitsLibrary.Dataset> sets = tab.selected();
        if (lib == null || sets.isEmpty())
            return;
        JSONArray keys = new JSONArray();
        for (FitsLibrary.Dataset set : sets)
            keys.put(set.key());
        JSONObject params = new JSONObject().put("dataset_keys", keys);
        run(parent, "Asking HelioFITS Core what would go...", lib.root(), "library.delete", new JSONObject(params.toMap()).put("dry_run", true), QUICK, plan -> {
            int files = plan.optInt("files");
            if (files == 0) {
                JOptionPane.showMessageDialog(parent, "Nothing to delete: those datasets hold no files now.", "Delete from Library", JOptionPane.INFORMATION_MESSAGE);
                tab.refresh();
                return;
            }
            String names = sets.size() == 1 ? sets.getFirst().label() : sets.size() + " datasets";
            int answer = JOptionPane.showConfirmDialog(parent,
                    "Delete " + String.format("%,d", files) + (files == 1 ? " file" : " files") + " (" + FitsLibraryTab.diskSize(plan.optLong("bytes_released"))
                            + ") of " + names + " from the FITS library?\n\n"
                            + "The files are removed from disk and from the library's index. This cannot be undone here;\n"
                            + "the data downloads again only if something asks the archive for it.",
                    "Delete from Library", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION)
                return;
            run(parent, "Deleting...", lib.root(), "library.delete", new JSONObject(params.toMap()).put("dry_run", false), QUICK, done -> tab.refresh());
        });
    }

    private interface Then {
        void accept(JSONObject out);
    }

    /** One Core call off the event thread; the answer comes back on it. A missing Core is said, never thrown. */
    private static void run(Component parent, String busy, Path root, String method, JSONObject params, Duration wait, Then then) {
        java.awt.Cursor before = parent.getCursor();
        parent.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        org.helioviewer.jhv.app.Log.info(busy);
        new SwingWorker<JSONObject, Void>() {
            @Override
            @Nullable
            protected JSONObject doInBackground() throws IOException {
                return FitsCoreIngest.call(root, method, params, wait);
            }

            @Override
            protected void done() {
                parent.setCursor(before);
                JSONObject out;
                try {
                    out = get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    Message.err("HelioFITS Core could not do that", cause.getMessage() == null ? "See the log for details." : cause.getMessage(), cause);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (out == null)
                    Message.warn("No HelioFITS Core", NO_CORE + ".");
                else
                    then.accept(out);
            }
        }.execute();
    }

}
