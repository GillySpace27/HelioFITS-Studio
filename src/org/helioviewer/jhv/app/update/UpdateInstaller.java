package org.helioviewer.jhv.app.update;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.WindowConstants;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.ExitHooks;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Session;
import org.helioviewer.jhv.gui.DesktopIntegration;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.NetClient;
import org.helioviewer.jhv.thread.AppThread;

/**
 * "Download and Install": keep the session safe, fetch the release's file, hand it to the desktop.
 *
 * <p>In that order, and each step only after the one before it worked:
 * <ol>
 * <li>The session is written now, through the synchronous save the quit path uses, and the States
 * and Settings folders (sessions, autosaves, recovery copies, settings) are COPIED into
 * ~/HFStudio/Backups/before-&lt;version&gt;-&lt;time&gt;. If any copy fails the update stops there and
 * says so. Nothing is moved or deleted.
 * <li>The file is downloaded to ~/Downloads (else the HFStudio downloads folder) under a temporary
 * ".part" name, its length checked against what GitHub lists and its SHA-256 against the one the
 * release states, then renamed. A file of the same name already there is reused when it checks out,
 * and never overwritten when it does not.
 * <li>On a Mac the disk image is opened, which mounts it and shows the window with Applications to drag
 * onto; elsewhere the file is shown in the file manager. The user replaces the app; this never
 * touches /Applications or anything installed, and never asks for an administrator password.
 * <li>"Quit Now" quits through ExitHooks, the same path as File > Quit, which saves the session
 * (Session.confirmExitAndAutosave). The next launch, on the new version, reopens it the usual way.
 * </ol>
 */
public final class UpdateInstaller {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** From the EDT, when the user chose Download and Install. */
    public static void install(ReleaseFeed.Release release) {
        SessionBackup.Result backup;
        try {
            backup = protectSession(release.version(), LocalDateTime.now(), Session::saveForUpdate);
        } catch (SaveFailed e) {
            Log.error("Update stopped: the session could not be saved", e);
            Message.err("Update stopped", "Could not save your session; update stopped, nothing downloaded.\n\n"
                    + "The session file was not confirmed as written, so it may hold an older scene. Nothing was "
                    + "copied, downloaded or changed. Save the session yourself (File > Save Session As...) and try again.\n\n"
                    + e.getMessage());
            return;
        } catch (IOException | RuntimeException e) {
            Log.error("Update stopped: the session could not be backed up", e);
            Message.err("Update stopped", "Your session and settings could not be copied to a backup, so nothing was "
                    + "downloaded and nothing was changed. Your session is as it was.\n\n" + e.getMessage());
            return;
        }
        Log.info("Before updating to " + release.version() + ": copied " + backup.files() + " files (" + backup.bytes()
                + " bytes) to " + backup.folder());
        Path dir;
        try {
            dir = downloadFolder();
        } catch (IOException e) {
            Message.err("Update stopped", "There is no folder to download into: " + e.getMessage()
                    + "\n\nYour session and its backup in " + backup.folder() + " are untouched.");
            return;
        }
        download(release, dir, backup.folder());
    }

    static String backupName(String version, LocalDateTime now) {
        return "before-" + version + "-" + now.format(STAMP);
    }

    /** Session files worth keeping: sessions, their .bak, the window registry. Not a session's .data.zip. */
    static boolean isSessionFile(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".jhv") || n.endsWith(".json") || n.endsWith(".bak");
    }

    /** Saves the session now: the file written, null when there was nothing to write, or a throw when unconfirmed. */
    interface SessionSaver {
        @Nullable
        File save() throws IOException;
    }

    /** The session could not be confirmed as saved, so nothing was copied and the update stops. */
    static final class SaveFailed extends IOException {
        private static final long serialVersionUID = 1L;

        SaveFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // Step 1, on the EDT. Throws SaveFailed when the save is not confirmed (before anything is copied),
    // IOException when a copy fails. UpdateBackupCheck runs it with a stand-in saver.
    static SessionBackup.Result protectSession(String version, LocalDateTime now, SessionSaver saver) throws IOException {
        File written;
        try {
            written = saver.save();
        } catch (IOException | RuntimeException e) {
            throw new SaveFailed(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
        if (written != null && (!written.isFile() || written.length() == 0))
            throw new SaveFailed("the session file " + written + " is missing or empty after saving", null);
        Path states = Directories.STATES.getFile().toPath().toAbsolutePath();
        Map<String, SessionBackup.Source> sources = new LinkedHashMap<>();
        sources.put("States", new SessionBackup.Source(states, UpdateInstaller::isSessionFile));
        sources.put("Settings", new SessionBackup.Source(Directories.SETTINGS.getFile().toPath(), p -> true));
        File current = Session.currentSessionFile();
        if (current != null && !current.toPath().toAbsolutePath().startsWith(states)) // a named project kept elsewhere
            sources.put(current.getName(), new SessionBackup.Source(current.toPath(), p -> true));
        return SessionBackup.copy(Directories.BACKUPS.getFile().toPath(), backupName(version, now), sources);
    }

    private static Path downloadFolder() throws IOException {
        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        if (Files.isDirectory(downloads) && Files.isWritable(downloads))
            return downloads;
        Path own = Directories.DOWNLOADS.getFile().toPath();
        Files.createDirectories(own);
        return own;
    }

    // ---- step 2: the download -------------------------------------------------------------------

    private static void download(ReleaseFeed.Release release, Path dir, Path backupFolder) {
        ReleaseFeed.Asset asset = release.asset();
        AtomicBoolean cancelled = new AtomicBoolean();

        JDialog dialog = new JDialog(MainFrame.get(), "Downloading HelioFITS Studio " + release.version(), false);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JProgressBar bar = new JProgressBar(0, 1000);
        JLabel label = new JLabel("Downloading " + asset.name() + " to " + dir);
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            cancelled.set(true);
            cancel.setEnabled(false);
            label.setText("Cancelling...");
        });
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                cancel.doClick();
            }
        });
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(label, BorderLayout.PAGE_START);
        panel.add(bar, BorderLayout.CENTER);
        JPanel south = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.TRAILING, 0, 0));
        south.add(cancel);
        panel.add(south, BorderLayout.PAGE_END);
        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setSize(Math.max(dialog.getWidth(), 480), dialog.getHeight());
        dialog.setLocationRelativeTo(MainFrame.get());

        long total = asset.size();
        AtomicLong shown = new AtomicLong(-1);
        LongConsumer progress = got -> {
            long permille = total > 0 ? Math.min(1000, got * 1000 / total) : 0;
            if (shown.getAndSet(permille) != permille) // at most a thousand repaints, however fast the line
                EventQueue.invokeLater(() -> {
                    bar.setValue((int) permille);
                    if (!cancelled.get())
                        label.setText("Downloading " + asset.name() + ": " + (got >> 20) + " of " + (total >> 20) + " MB");
                });
        };

        AppThread.create(() -> {
            try {
                Path file = fetch(asset, dir, cancelled, progress);
                Log.info("Update downloaded and verified: " + file);
                EventQueue.invokeLater(() -> {
                    dialog.dispose();
                    handOver(release, file, backupFolder);
                });
            } catch (CancellationException e) {
                Log.info("Update download cancelled");
                EventQueue.invokeLater(dialog::dispose);
            } catch (IOException | URISyntaxException | RuntimeException e) {
                Log.warn("Update download failed", e);
                EventQueue.invokeLater(dialog::dispose);
                Message.err("Update download failed", "HelioFITS Studio " + release.version() + " could not be downloaded: "
                        + e.getMessage() + "\n\nNothing was installed. Your session is untouched, and its backup is in\n" + backupFolder
                        + "\n\nThe release is also on " + release.pageUrl());
            }
        }, "HFS-DownloadUpdate").start();
        dialog.setVisible(true);
    }

    /**
     * Download {@code asset} into {@code dir}, verified, and return where it landed. Throws
     * CancellationException when {@code cancelled} is set. Off the EDT.
     */
    static Path fetch(ReleaseFeed.Asset asset, Path dir, AtomicBoolean cancelled, LongConsumer progress)
            throws IOException, URISyntaxException {
        Path existing = dir.resolve(asset.name());
        if (Files.isRegularFile(existing)) {
            try {
                verify(Files.size(existing), sha256(existing), asset);
                Log.info("Update: reusing the verified " + existing);
                return existing;
            } catch (IOException e) {
                Log.info("Update: " + existing + " is not this release's file (" + e.getMessage() + "); downloading beside it");
            }
        }

        Path part = Files.createTempFile(dir, asset.name() + ".", ".part"); // this download's own, new file
        boolean finished = false;
        try {
            MessageDigest sha = sha256Digest();
            long got = 0;
            try (NetClient nc = NetClient.of(new URI(asset.url()), false, NetClient.NetCache.BYPASS);
                 InputStream in = nc.getStream();
                 OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled.get())
                        throw new CancellationException();
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    got += n;
                    if (got > asset.size())
                        throw new IOException("the server sent more than the " + asset.size() + " bytes the release lists");
                    progress.accept(got);
                }
            }
            verify(got, HexFormat.of().formatHex(sha.digest()), asset);
            Path target = freeName(dir, asset.name());
            Files.move(part, target); // no REPLACE_EXISTING: never over a file that is already there
            finished = true;
            return target;
        } finally {
            if (!finished)
                try {
                    Files.deleteIfExists(part); // only the partial file this call created, never anything else
                } catch (IOException e) {
                    Log.warn("Could not remove the partial download " + part, e);
                }
        }
    }

    /** Throws unless the length matches what GitHub lists and, when the release states one, the SHA-256 matches. */
    static void verify(long length, String sha256, ReleaseFeed.Asset asset) throws IOException {
        if (length != asset.size())
            throw new IOException("the download is " + length + " bytes, the release lists " + asset.size());
        if (asset.sha256() == null)
            Log.info("Update: " + asset.name() + " has no SHA-256 in its release; checked by length only");
        else if (!asset.sha256().equalsIgnoreCase(sha256))
            throw new IOException("the download's SHA-256 is " + sha256 + ", the release states " + asset.sha256());
    }

    /** {@code name} in {@code dir}, or "name (2).ext", "(3)", ... when that is taken. Never an existing file. */
    static Path freeName(Path dir, String name) {
        Path p = dir.resolve(name);
        if (!Files.exists(p))
            return p;
        String ext = name.endsWith(".tar.gz") ? ".tar.gz" : name.lastIndexOf('.') > 0 ? name.substring(name.lastIndexOf('.')) : "";
        String base = name.substring(0, name.length() - ext.length());
        for (int i = 2; ; i++) {
            p = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(p))
                return p;
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest sha = sha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0)
                sha.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) { // every Java has it; this is the compiler's question
            throw new IOException(e);
        }
    }

    // ---- steps 3 and 4: hand the file to the desktop, offer to quit -------------------------------

    private static void handOver(ReleaseFeed.Release release, Path file, Path backupFolder) {
        String v = release.version();
        boolean dmg = Platform.isMacOS() && file.getFileName().toString().endsWith(".dmg");
        boolean opened = false;
        if (dmg)
            try {
                new ProcessBuilder("open", file.toString()).start(); // mounts it as the user and shows its window
                opened = true;
            } catch (IOException e) {
                Log.warn("Could not open " + file, e);
            }
        if (!opened)
            DesktopIntegration.reveal(file.toFile());

        StringBuilder text = new StringBuilder();
        if (opened)
            text.append("The HelioFITS Studio ").append(v).append(" disk image is open in Finder.\n\n")
                    .append("Click Quit Now, then drag HelioFITS Studio to Applications, replacing the old copy, then reopen it. ")
                    .append("Your session is saved and will reopen.");
        else {
            text.append("HelioFITS Studio ").append(v).append(" was downloaded to\n").append(file).append("\n\n");
            if (dmg)
                text.append("Click Quit Now, then open it and drag HelioFITS Studio to Applications, replacing the old copy, then reopen it.");
            else {
                // A Mac on the zip runs it beside the app it had, so "this copy's folder" (inside a .app) is no guide.
                String from = Platform.isMacOS() ? null : installFolder();
                text.append(from == null ? "Click Quit Now, then unpack it and start the new one."
                        : "Click Quit Now, then unpack it in place of this copy, which runs from\n" + from + "\nand start the new one.");
            }
            text.append(" Your session is saved and will reopen.");
            if (ReleaseFeed.needsOwnJava(release.asset().name()))
                text.append("\n\nThis is the cross-platform zip, which carries no Java of its own: it needs Java 25 installed. ")
                        .append("The release page says how:\n").append(AppInfo.downloadURL);
        }
        if (Session.liveWindowCount() > 1)
            text.append("\n\nOther HelioFITS Studio windows are open. Quit them too before installing; each saves its own session.");
        text.append("\n\nA copy of your session and settings is in\n").append(backupFolder);

        Object[] options = {"Quit Now", "Later"};
        int choice = JOptionPane.showOptionDialog(MainFrame.get(), text.toString(), "Install HelioFITS Studio " + v,
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (choice == 0 && ExitHooks.exitProgram()) // File > Quit's own path: it saves the session, and can still be cancelled
            System.exit(0);
    }

    // The folder holding the running jar, to say where the new copy goes; null when that is not a file.
    @Nullable
    private static String installFolder() {
        try {
            Path jar = Path.of(UpdateInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path parent = jar.getParent();
            return parent == null ? null : parent.toString();
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private UpdateInstaller() {}
}
