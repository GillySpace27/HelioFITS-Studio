package org.helioviewer.jhv.app;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

import org.helioviewer.jhv.io.DataSources;
import org.helioviewer.jhv.io.Directories;

@SuppressWarnings("serial")
public class Settings {

    private static final Path userPath = Path.of(Directories.SETTINGS.getPath(), "user.properties");
    private static final Properties defaults = new Properties() {
        {
            setProperty("startup.sampHub", "true");
            setProperty("display.normalizeAIA", "true");
            setProperty("display.normalizeRadius", "false");
            setProperty("display.time", "Observer");
            setProperty("video.format", "H264");
            setProperty("dataSources.defaultServer", "IAS");
        }
    };
    private static final Properties settings = new Properties(defaults);
    /**
     * Whether this process has read the settings file. Until it has, nothing may be written over it.
     *
     * <p>write() stores the whole table, so a process that never called load() stores a table holding
     * only the keys it set itself, and the atomic move then puts that in place of the user's file. The
     * application always loads first, but the self-checks in extra/test run the same classes in their
     * own JVMs and most of them never isolate user.home: one of them setting display.skyBase left
     * Gilly's settings as that one line on 2026-09-11, toolbar order, sidebars and palettes included.
     * Hence the dashboard "not keeping" its configuration. The move made a torn write impossible; this
     * makes a write from a process that never saw the file impossible.
     */
    private static boolean loaded;

    public static void load() {
        if (Files.exists(userPath)) {
            try (BufferedReader reader = Files.newBufferedReader(userPath)) {
                settings.load(reader);
                loaded = true;
            } catch (Exception e) {
                Log.warn(e); // not loaded: an unreadable file is kept for the user, never replaced by a blank table
            }
            if (loaded)
                keepDailyCopy();
        } else
            loaded = true; // nothing there to lose

        if (getProperty("path.local") == null)
            setProperty("path.local", Directories.DOWNLOADS.getPath());
        if (getProperty("path.state") == null)
            setProperty("path.state", Directories.STATES.getPath());
        String server = getProperty("dataSources.defaultServer");
        if (server == null || DataSources.getServerSetting(server, "API.getDataSources") == null)
            setProperty("dataSources.defaultServer", "IAS");
    }

    /**
     * One copy of the file per day it is loaded, the last seven kept, beside it.
     *
     * <p>The guard in write() stops the one cause of lost settings that has been identified. On
     * 2026-09-12 the file lost its toolbar order, every palette's sidebar and the panel lock again,
     * between 18:09 and 18:21, and which process did it was never established. This is for that
     * kind of day: whatever did it, yesterday's file, and this morning's, are still there to put
     * back. A copy per day rather than per launch, because a launch after the damage would
     * otherwise copy the damage over the last good one.
     */
    private static void keepDailyCopy() {
        Path today = userPath.resolveSibling("user.properties." + java.time.LocalDate.now());
        try {
            if (!Files.exists(today))
                Files.copy(userPath, today);
            try (java.util.stream.Stream<Path> siblings = Files.list(userPath.getParent())) {
                siblings.map(p -> p.getFileName().toString())
                        .filter(n -> n.matches("user\\.properties\\.\\d{4}-\\d{2}-\\d{2}"))
                        .sorted(java.util.Comparator.reverseOrder()) // ISO dates sort as dates
                        .skip(7)
                        .forEach(n -> {
                            try {
                                Files.delete(userPath.resolveSibling(n));
                            } catch (Exception ignored) {
                                // an old copy left behind costs a kilobyte
                            }
                        });
            }
        } catch (Exception e) {
            Log.warn(e); // never a reason not to start
        }
    }

    public static void setProperty(String key, String val) {
        if (!val.equals(getProperty(key))) {
            settings.setProperty(key, val);
            write();
        }
    }

    /**
     * Write every setting through a temporary file and move it into place.
     *
     * <p>This used to open the real file directly, which truncates it before a single byte is
     * written. Every preference in the application goes through here, and some of them are written
     * on a timer while the window is being dragged, so the window in which the file is empty is
     * open often. A quit, a crash or a kill landing inside it left an empty user.properties, the
     * next launch read no settings at all, and each component then wrote its own key back into an
     * otherwise blank file: the HDR mapping, the interpolation, the open palettes, the panel
     * sections and the recent sessions were simply gone. Seen on 2026-09-05, 17 keys down to 10.
     * A move is atomic, so a reader sees either the old file or the new one.
     */
    private static void write() {
        // Silently, not with a warning: the process that reaches this is a check or a tool, where the
        // in-memory value is all it wanted, and logging would open a log file in the real home too.
        if (!loaded && Files.exists(userPath))
            return;
        Path temp = userPath.resolveSibling("user.properties.tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temp)) {
                settings.store(writer, null);
            }
            try {
                Files.move(temp, userPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) { // e.g. across filesystems
                Files.move(temp, userPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Log.warn(e);
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // nothing useful to do; a stale temp is harmless and the next write replaces it
            }
        }
    }

    public static String getProperty(String key) {
        return settings.getProperty(key);
    }

    /**
     * One setting, read straight off disk before {@link #load} has run.
     *
     * <p>macOS fixes the application's appearance when the Cocoa application starts, which is
     * before the data sources and therefore before the settings can be loaded (see
     * {@link Theme#startupIsDark}). Nothing else should need this: everything that runs after
     * start-up reads the loaded table.
     */
    static String peekProperty(String key) {
        if (!Files.isReadable(userPath))
            return null;
        Properties peek = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(userPath)) {
            peek.load(reader);
        } catch (Exception e) {
            return null;
        }
        return peek.getProperty(key);
    }

}
