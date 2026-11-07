package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.io.FitsCoreIngest;
import org.helioviewer.jhv.io.FitsLibrary;
import org.helioviewer.jhv.time.TimeUtils;
import org.json.JSONObject;

/**
 * The FITS Library tab of the Settings dialog: whether HelioFITS Core is on this computer, what its library
 * holds, and whether Studio is adding its downloads to it. With no HelioFITS Core, a way to get it.
 *
 * <p>Everything here goes through what Studio already uses. The library is found and read by
 * {@link FitsLibrary} under HelioFITS Core's reader contract, read-only. The core is the one
 * {@link FitsCoreIngest} hands downloads to, and it is asked only {@code core.hello} ({@code heliad.hello} on a core from before the rename),
 * which reports its version and the library it serves (docs/api.md in the HelioFITS Core repository). Asking may start
 * that core, which is what the next download would do anyway; nothing here writes to the library.
 *
 * <p>The tab reads again each time it is shown, and on Check Again, so a HelioFITS Core installed or
 * started while Studio runs shows up without a restart.
 *
 * <p>Choose Folder... sets where the library is ({@link FitsCoreIngest#useLibrary}, {@code hfcore
 * --library DIR init}): an empty or new folder becomes a new library, a folder that already is one is
 * used as it is, and the library used before stays where it was. Studio's FITS downloads then move
 * into the chosen library (one store, {@link org.helioviewer.jhv.io.LibraryStore}).
 */
@SuppressWarnings("serial")
final class FitsLibrarySettings extends JPanel {

    /**
     * Where "Get Cache Manager" goes. The HelioFITS Cache Manager has no download page yet; the Help me decide page lists it
     * with its status. Point this at the download page once there is one.
     */
    static final String GET_URL = "https://gilly.space/heliosoftware/decide/";

    private static final Duration HELLO_WAIT = Duration.ofSeconds(15);
    static final String NONE_FOUND = "Not found, so Studio keeps FITS downloads in its own cache. "
            + "Studio for macOS includes HelioFITS Core; elsewhere, set HFCORE_CORE to a pyHelioFITS install";
    private static final String TITLE = "FITS library";

    private final JLabel headline = new JLabel(" ");
    private final JLabel library = new JLabel(" ");
    private final JLabel contents = new JLabel(" ");
    private final JLabel app = new JLabel(" ");
    private final JLabel core = new JLabel(" ");
    private final JLabel downloads = new JLabel(" ");
    private final JButton showFolder = new JButton("Show Folder");
    private final JButton openCacheManager = new JButton("Open Cache Manager App");
    private final JButton getCacheManager = new JButton("Get Cache Manager");
    private final JButton again = new JButton("Check Again");
    private final JButton againMissing = new JButton("Check Again"); // a component has one parent, and each face needs one
    private final JButton choose = new JButton("Choose Folder...");
    private final JButton chooseMissing = new JButton("Choose Folder...");
    private final JLabel missingHead = new JLabel(" ");
    private final JLabel missingBody = new JLabel(" ");
    private final SettingsForm found = new SettingsForm();
    private final SettingsForm missing = new SettingsForm();

    @Nullable
    private FitsLibrary.Location lib;
    private int reads; // so a slow read that lands after a newer one is dropped

    FitsLibrarySettings() {
        super(new BorderLayout());

        showFolder.addActionListener(e -> {
            if (lib != null)
                DesktopIntegration.reveal(lib.root().toFile());
        });
        openCacheManager.setToolTipText("Open the HelioFITS Cache Manager on a search for the master time range");
        openCacheManager.addActionListener(e -> DesktopIntegration.openURL(FitsLibrary.link(
                MoviePanel.getInstance().getStartTime(), MoviePanel.getInstance().getEndTime(), null)));
        getCacheManager.setToolTipText(GET_URL);
        getCacheManager.addActionListener(e -> DesktopIntegration.openURL(GET_URL));
        again.addActionListener(e -> refresh());
        againMissing.addActionListener(e -> refresh());
        for (JButton b : new JButton[]{choose, chooseMissing}) {
            b.setToolTipText("Put the FITS library in another folder or on another drive, or make a new one");
            b.addActionListener(e -> chooseFolder());
        }

        found.row("FITS library:", headline)
                .row("Library:", inline(library, showFolder, choose))
                .row("Holds:", contents)
                .row("App:", app)
                .row("Core:", core)
                .row("Downloads:", downloads)
                .row(null, inline(openCacheManager, again))
                .end();

        missing.row("FITS library:", missingHead)
                .row(null, missingBody)
                .row(null, inline(chooseMissing, getCacheManager, againMissing))
                .end();
    }

    /** As large as either face, so the dialog, packed while one shows, has room for the other. */
    @Override
    public java.awt.Dimension getPreferredSize() {
        java.awt.Dimension a = found.getPreferredSize(), b = missing.getPreferredSize();
        return new java.awt.Dimension(Math.max(a.width, b.width), Math.max(a.height, b.height));
    }

    private static JPanel inline(java.awt.Component... parts) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        panel.setOpaque(false);
        for (java.awt.Component part : parts) {
            if (panel.getComponentCount() > 0)
                panel.add(Box.createHorizontalStrut(6));
            panel.add(part);
        }
        return panel;
    }

    /** Look for HelioFITS Core again and fill the tab: the library at once, the core when it answers. */
    void refresh() {
        int read = ++reads;
        FitsLibrary.Location now = FitsLibrary.find();
        lib = now;
        removeAll();
        add(now == null ? missing : found, BorderLayout.CENTER);
        revalidate();
        repaint();
        if (now == null) {
            Path configDir = FitsLibrary.configDir();
            Path named = Files.exists(configDir.resolve("config.json")) ? FitsLibrary.configuredRoot(configDir) : null;
            boolean coreFound = FitsCoreIngest.coreFound();
            String[] text = missingText(named, coreFound, SettingsDialog.homeRelative(configDir));
            missingHead.setText(text[0]);
            missingBody.setText("<html><body style='width:380px'>" + text[1] + "</body></html>");
            chooseMissing.setVisible(coreFound);
            return;
        }

        Path root = now.root();
        headline.setText("On: Studio keeps its FITS downloads here");
        library.setText(SettingsDialog.homeRelative(root));
        library.setToolTipText(root.toString());
        Path appPath = FitsLibrary.app();
        app.setText(appPath != null ? "Installed at " + SettingsDialog.homeRelative(appPath)
                : System.getProperty("os.name", "").startsWith("Mac") ? "Not in /Applications or ~/Applications"
                : "Not looked for (the HelioFITS Cache Manager is looked for on macOS only)");
        openCacheManager.setVisible(appPath != null);
        contents.setText("Reading the library...");
        boolean coreFound = FitsCoreIngest.coreFound(root);
        core.setText(coreFound ? "Asking HelioFITS Core..." : NONE_FOUND);
        choose.setVisible(coreFound);
        downloads.setText(downloadsText(coreFound, FitsCoreIngest.consumes(), FitsCoreIngest.cacheOfferedThrough(root)));

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws SQLException, FitsLibrary.TooNew {
                return holds(FitsLibrary.datasets(now, 0, 0));
            }

            @Override
            protected void done() {
                if (read == reads)
                    contents.setText(answer(this));
            }
        }.execute();

        if (!coreFound)
            return;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws IOException {
                JSONObject hello = FitsCoreIngest.hello(root, HELLO_WAIT);
                return hello == null ? NONE_FOUND : coreText(hello, root);
            }

            @Override
            protected void done() {
                if (read == reads)
                    core.setText(answer(this));
            }
        }.execute();
    }

    /** The worker's text, or why it has none. */
    private static String answer(SwingWorker<String, Void> worker) {
        try {
            return worker.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof FitsLibrary.TooNew)
                return cause.getMessage();
            return "Could not ask: " + (cause.getMessage() == null ? "see the log for details" : cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return " ";
        }
    }

    static String holds(List<FitsLibrary.Dataset> sets) {
        long frames = 0, bytes = 0;
        for (FitsLibrary.Dataset set : sets) {
            frames += set.frames();
            bytes += set.bytes();
        }
        return String.format("%,d", sets.size()) + (sets.size() == 1 ? " dataset, " : " datasets, ")
                + String.format("%,d", frames) + (frames == 1 ? " frame, " : " frames, ") + size(bytes);
    }

    static String coreText(JSONObject hello, Path root) {
        StringBuilder text = new StringBuilder("HelioFITS Core ").append(hello.optString("version", "(version not given)")).append(" is running");
        if (hello.optBoolean("readonly", false))
            text.append(", read-only");
        String served = hello.optString("library_root", "");
        if (!served.isEmpty() && !Path.of(served).equals(root))
            text.append(", serving another library: ").append(served);
        return text.toString();
    }

    /**
     * The missing face's headline and body (HTML without the wrapper): a library set to a folder
     * that is not there now (a drive not connected), no library yet with a core to make one, or no
     * core at all.
     *
     * @param named     the library the config names, or null when there is no config
     * @param configDir where Studio looks for HelioFITS Core's config, as the user reads it
     */
    static String[] missingText(@Nullable Path named, boolean coreFound, String configDir) {
        if (named != null)
            return new String[]{"Not connected",
                    "The FITS library is set to " + named + ", which is not there now. If it is on a drive, connect it and press "
                            + "Check Again; until then FITS downloads stay in Studio's cache."
                            + (coreFound ? " Choose Folder... uses another folder instead." : "")};
        if (coreFound)
            return new String[]{"None yet",
                    "Studio keeps FITS downloads in its own cache until there is a library. With one, each FITS download has one copy, "
                            + "in the library, and the library is listed in New Image Layer and searchable from Python. Choose Folder... "
                            + "makes one in an empty or new folder (on another drive too), or uses a folder that already holds one."
                            + (Platform.isMacOS() ? " Studio also makes one at ~/HelioFITSLibrary by itself at the first FITS download." : "")};
        return new String[]{"Not available",
                "Studio keeps FITS downloads in its own cache, and everything works without a library. A library needs HelioFITS Core: "
                        + "Studio for macOS includes it; on Windows and Linux it comes with a later release, or set HFCORE_CORE to a "
                        + "pyHelioFITS install now.<br><br>Studio looks for the library's settings (HelioFITS Core's) in " + configDir
                        + ", or in the folder HFCORE_HOME names."};
    }

    static String downloadsText(boolean coreFound, boolean moves, long offeredThrough) {
        if (!coreFound)
            return "Kept in Studio's cache only, until a HelioFITS Core is found";
        if (moves)
            return "Studio moves each FITS download into this library, so there is one copy";
        String text = "Studio adds a copy of each FITS download to this library (this HelioFITS Core cannot take files over)";
        return offeredThrough == Long.MIN_VALUE ? text : text + "; its cache was offered up to " + TimeUtils.formatDate(offeredThrough);
    }

    /**
     * Ask for a folder and make it the FITS library: a new or empty folder becomes a new library, a
     * library is used as it is. The library used before stays where it is, with its files.
     */
    private void chooseFolder() {
        Path before = lib == null ? null : lib.root();
        Path start = before != null ? before.getParent() : FitsLibrary.defaultRoot().getParent();
        JFileChooser chooser = new JFileChooser(start == null ? null : start.toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose the FITS library folder");
        if (chooser.showDialog(this, "Use This Folder") != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null)
            return;
        Path root = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (root.equals(before))
            return;
        if (!FitsLibrary.canMakeLibraryAt(root)) {
            Message.warn(TITLE, root + " already holds other files. Choose an empty folder, a new one, or a folder that is already a FITS library.");
            return;
        }
        String ask = (FitsLibrary.isLibrary(root) ? "Use the FITS library in " : "Make a FITS library in ") + root + "?\n\n"
                + "FITS files in Studio's download cache move into it, and new FITS downloads go there."
                + (before == null ? "" : "\nThe library in " + before + " stays where it is, with its files; Studio stops using it.");
        if (JOptionPane.showConfirmDialog(this, ask, TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION)
            return;
        choose.setEnabled(false);
        chooseMissing.setEnabled(false);
        headline.setText("Setting up " + root + "...");
        missingHead.setText("Setting up " + root + "...");
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws IOException {
                return FitsCoreIngest.useLibrary(root);
            }

            @Override
            protected void done() {
                choose.setEnabled(true);
                chooseMissing.setEnabled(true);
                try {
                    List<String> warnings = get();
                    if (!warnings.isEmpty())
                        Message.warn(TITLE, "The FITS library is now " + root + ". HelioFITS Core warns:\n\n" + String.join("\n", warnings));
                    FitsCoreIngest.importCacheInBackground(); // the cache moves into it, once
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    Message.err(TITLE, "Could not make " + root + " the FITS library: " + cause.getMessage(), cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                refresh();
            }
        }.execute();
    }

    private static String size(long bytes) {
        if (bytes >= 1L << 30)
            return String.format("%.1f GB", bytes / (double) (1L << 30));
        return bytes >= 1L << 20 ? (bytes >> 20) + " MB" : bytes >= 1L << 10 ? (bytes >> 10) + " KB" : bytes + " bytes";
    }

}
