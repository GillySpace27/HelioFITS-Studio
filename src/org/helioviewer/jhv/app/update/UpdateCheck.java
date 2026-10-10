package org.helioviewer.jhv.app.update;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.swing.JOptionPane;
import javax.swing.Timer;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Session;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.gui.dialog.UpdateAvailableDialog;
import org.helioviewer.jhv.io.NetClient;
import org.helioviewer.jhv.thread.AppThread;

import org.json.JSONArray;

/**
 * Whether a newer HelioFITS Studio has been released, asked of GitHub's releases list.
 *
 * <p>Automatically, a few seconds after the window opens, at most once a day, and only when the
 * "Check for updates at startup" setting is on (the default). That check is silent when it cannot
 * reach GitHub, is rate limited, or finds nothing: it writes a line to the log and that is all. The
 * Help menu's check says what happened either way, and offers a version the user chose to skip.
 * Neither ever runs on the EDT or holds up the start.
 */
public final class UpdateCheck {

    static final String RELEASES_API = "https://api.github.com/repos/GillySpace27/HelioFITS-Studio/releases?per_page=30";
    public static final String ENABLED_KEY = "update.checkAtStartup";
    static final String LAST_CHECK_KEY = "update.lastCheck";
    static final String SKIP_KEY = "update.skipVersion";
    private static final int STARTUP_DELAY_MS = 8_000;

    // One check at a time: a second click while the first is out would only ask GitHub twice.
    private static final AtomicBoolean inFlight = new AtomicBoolean();

    /** On unless the user turned it off. */
    public static boolean enabledAtStartup() {
        return !"false".equals(Settings.getProperty(ENABLED_KEY));
    }

    public static void setEnabledAtStartup(boolean on) {
        Settings.setProperty(ENABLED_KEY, Boolean.toString(on));
    }

    /**
     * The automatic check, from startup on the EDT. Only the window the user launched checks: an extra
     * window is a process of the same install. A script's launch and CI do not check either.
     */
    public static void atStartup() {
        if (GraphicsEnvironment.isHeadless() || Session.isExtraWindow() || System.getenv("CI") != null || !enabledAtStartup())
            return;
        if (!ReleaseFeed.due(System.currentTimeMillis(), lastCheck()))
            return;
        Timer later = new Timer(STARTUP_DELAY_MS, e -> start(false));
        later.setRepeats(false);
        later.start();
    }

    /** Help > Check for Updates. */
    public static void checkNow() {
        start(true);
    }

    private static long lastCheck() {
        try {
            String v = Settings.getProperty(LAST_CHECK_KEY);
            return v == null ? 0 : Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // On the EDT. The time is recorded when the automatic check goes out, not when it succeeds, so an
    // offline computer asks once a day rather than at every launch.
    private static void start(boolean manual) {
        if (!inFlight.compareAndSet(false, true))
            return;
        if (!manual)
            Settings.setProperty(LAST_CHECK_KEY, Long.toString(System.currentTimeMillis()));
        AppThread.create(() -> {
            try {
                ReleaseFeed.Release newest = fetchNewest();
                EventQueue.invokeLater(() -> show(newest, manual));
            } catch (IOException | URISyntaxException | RuntimeException e) { // RuntimeException covers a malformed reply (JSONException)
                if (manual) {
                    Log.warn(e);
                    Message.warn("Update check error", "While checking for a newer version got " + e.getMessage());
                } else
                    Log.info("Automatic update check did not complete: " + e.getMessage());
            } finally {
                inFlight.set(false);
            }
        }, "HFS-CheckUpdate").start();
    }

    @Nullable
    private static ReleaseFeed.Release fetchNewest() throws IOException, URISyntaxException {
        String text;
        try (NetClient nc = NetClient.of(new URI(RELEASES_API), false, NetClient.NetCache.NETWORK)) {
            text = nc.getSource().readUtf8();
        }
        return ReleaseFeed.newest(new JSONArray(text), ReleaseFeed.os(System.getProperty("os.name")),
                ReleaseFeed.arm(System.getProperty("os.arch")));
    }

    private static void show(@Nullable ReleaseFeed.Release newest, boolean manual) {
        String running = AppInfo.version;
        String skipped = Settings.getProperty(SKIP_KEY);
        if (ReleaseFeed.shouldOffer(newest, running, skipped, manual)) {
            Log.info("Found newer version " + newest.version());
            UpdateAvailableDialog.show(newest, running);
            return;
        }
        if (newest != null && ReleaseFeed.isNewer(newest.version(), running))
            Log.info("Newer version " + newest.version() + " was skipped by the user");
        else
            Log.info("Update check: " + running + " is current" + (newest == null ? " (no installable release found)" : ""));
        if (!manual)
            return;
        String text = ReleaseFeed.parse(running) == null
                ? "This build (" + AppInfo.label() + ") does not carry a release version, so it cannot be compared.\nReleases: " + AppInfo.downloadURL
                : "You are running the latest HelioFITS Studio version (" + running + ").";
        JOptionPane.showMessageDialog(MainFrame.get(), text, "Check for Updates", JOptionPane.INFORMATION_MESSAGE);
    }

    /** "Skip This Version": the automatic check stays quiet about this one version. */
    public static void skip(String version) {
        Settings.setProperty(SKIP_KEY, version);
    }

    private UpdateCheck() {}
}
