package org.helioviewer.jhv.gui.dialog;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.component.MoviePanel;
import org.helioviewer.jhv.io.HeliadIngest;
import org.helioviewer.jhv.io.HeliadLibrary;
import org.helioviewer.jhv.time.TimeUtils;
import org.json.JSONObject;

/**
 * The Heliad tab of the Settings dialog: whether Heliad is on this computer, what its library
 * holds, and whether Studio is adding its downloads to it. With no Heliad, a way to get it.
 *
 * <p>Everything here goes through what Studio already uses. The library is found and read by
 * {@link HeliadLibrary} under Heliad's reader contract, read-only. The core is the one
 * {@link HeliadIngest} hands downloads to, and it is asked only {@code heliad.hello}, which reports
 * its version and the library it serves (docs/api.md in the heliad repository). Asking may start
 * that core, which is what the next download would do anyway; nothing here writes to the library.
 *
 * <p>The tab reads again each time it is shown, and on Check Again, so a Heliad installed or
 * started while Studio runs shows up without a restart.
 */
@SuppressWarnings("serial")
final class HeliadSettings extends JPanel {

    /**
     * Where "Get Heliad" goes. Heliad has no download page yet; the Help me decide page lists it
     * with its status. Point this at the download page once there is one.
     */
    static final String GET_URL = "https://gilly.space/heliosoftware/decide/";

    private static final Duration HELLO_WAIT = Duration.ofSeconds(15);

    private final JLabel headline = new JLabel(" ");
    private final JLabel library = new JLabel(" ");
    private final JLabel contents = new JLabel(" ");
    private final JLabel app = new JLabel(" ");
    private final JLabel core = new JLabel(" ");
    private final JLabel downloads = new JLabel(" ");
    private final JButton showFolder = new JButton("Show Folder");
    private final JButton openHeliad = new JButton("Open Heliad");
    private final JButton getHeliad = new JButton("Get Heliad");
    private final JButton again = new JButton("Check Again");
    private final JButton againMissing = new JButton("Check Again"); // a component has one parent, and each face needs one
    private final SettingsForm found = new SettingsForm();
    private final SettingsForm missing = new SettingsForm();

    @Nullable
    private HeliadLibrary.Location lib;
    private int reads; // so a slow read that lands after a newer one is dropped

    HeliadSettings() {
        super(new BorderLayout());

        showFolder.addActionListener(e -> {
            if (lib != null)
                DesktopIntegration.reveal(lib.root().toFile());
        });
        openHeliad.setToolTipText("Open the Heliad app on a search for the master time range");
        openHeliad.addActionListener(e -> DesktopIntegration.openURL(HeliadLibrary.link(
                MoviePanel.getInstance().getStartTime(), MoviePanel.getInstance().getEndTime(), null)));
        getHeliad.setToolTipText(GET_URL);
        getHeliad.addActionListener(e -> DesktopIntegration.openURL(GET_URL));
        again.addActionListener(e -> refresh());
        againMissing.addActionListener(e -> refresh());

        found.row("Heliad:", headline)
                .row("Library:", inline(library, showFolder))
                .row("Holds:", contents)
                .row("App:", app)
                .row("Core:", core)
                .row("Downloads:", downloads)
                .row(null, inline(openHeliad, again))
                .end();

        missing.row("Heliad:", new JLabel("Not found on this computer."))
                .row(null, new JLabel("<html><body style='width:380px'>Heliad finds solar data, downloads it and keeps it "
                        + "in one library on your disk. With Heliad here, Studio lists that library in New Image Layer "
                        + "and adds its own FITS downloads to it.<br><br>Studio looks for Heliad's settings in "
                        + SettingsDialog.homeRelative(HeliadLibrary.configDir()) + ", or in the folder HELIAD_HOME names.</body></html>"))
                .row(null, inline(getHeliad, againMissing))
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

    /** Look for Heliad again and fill the tab: the library at once, the core when it answers. */
    void refresh() {
        int read = ++reads;
        HeliadLibrary.Location now = HeliadLibrary.find();
        lib = now;
        removeAll();
        add(now == null ? missing : found, BorderLayout.CENTER);
        revalidate();
        repaint();
        if (now == null)
            return;

        Path root = now.root();
        headline.setText("Found");
        library.setText(SettingsDialog.homeRelative(root));
        library.setToolTipText(root.toString());
        Path appPath = HeliadLibrary.app();
        app.setText(appPath != null ? "Installed at " + SettingsDialog.homeRelative(appPath)
                : System.getProperty("os.name", "").startsWith("Mac") ? "Not in /Applications or ~/Applications"
                : "Not looked for (the Heliad app is looked for on macOS only)");
        openHeliad.setVisible(appPath != null);
        contents.setText("Reading the library...");
        boolean coreFound = HeliadIngest.coreFound(root);
        core.setText(coreFound ? "Asking Heliad..." : "None found: install Heliad.app, or set HELIAD_CORE");
        downloads.setText(downloadsText(coreFound, HeliadIngest.cacheOfferedThrough(root)));

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws SQLException, HeliadLibrary.TooNew {
                return holds(HeliadLibrary.datasets(now, 0, 0));
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
                JSONObject hello = HeliadIngest.hello(root, HELLO_WAIT);
                return hello == null ? "None found: install Heliad.app, or set HELIAD_CORE" : coreText(hello, root);
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
            if (cause instanceof HeliadLibrary.TooNew)
                return cause.getMessage();
            return "Could not ask: " + (cause.getMessage() == null ? "see the log for details" : cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return " ";
        }
    }

    static String holds(List<HeliadLibrary.Dataset> sets) {
        long frames = 0, bytes = 0;
        for (HeliadLibrary.Dataset set : sets) {
            frames += set.frames();
            bytes += set.bytes();
        }
        return String.format("%,d", sets.size()) + (sets.size() == 1 ? " dataset, " : " datasets, ")
                + String.format("%,d", frames) + (frames == 1 ? " frame, " : " frames, ") + size(bytes);
    }

    static String coreText(JSONObject hello, Path root) {
        StringBuilder text = new StringBuilder("Heliad ").append(hello.optString("version", "(version not given)")).append(" is running");
        if (hello.optBoolean("readonly", false))
            text.append(", read-only");
        String served = hello.optString("library_root", "");
        if (!served.isEmpty() && !Path.of(served).equals(root))
            text.append(", serving another library: ").append(served);
        return text.toString();
    }

    static String downloadsText(boolean coreFound, long offeredThrough) {
        if (!coreFound)
            return "Kept in Studio's cache only, until a Heliad core is found";
        String text = "Studio adds each FITS download to this library";
        return offeredThrough == Long.MIN_VALUE ? text : text + "; its cache was offered up to " + TimeUtils.formatDate(offeredThrough);
    }

    private static String size(long bytes) {
        if (bytes >= 1L << 30)
            return String.format("%.1f GB", bytes / (double) (1L << 30));
        return bytes >= 1L << 20 ? (bytes >> 20) + " MB" : bytes >= 1L << 10 ? (bytes >> 10) + " KB" : bytes + " bytes";
    }

}
