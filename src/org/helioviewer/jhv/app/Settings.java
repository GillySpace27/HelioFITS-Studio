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

    public static void load() {
        if (Files.exists(userPath)) {
            try (BufferedReader reader = Files.newBufferedReader(userPath)) {
                settings.load(reader);
            } catch (Exception e) {
                Log.warn(e);
            }
        }

        if (getProperty("path.local") == null)
            setProperty("path.local", Directories.DOWNLOADS.getPath());
        if (getProperty("path.state") == null)
            setProperty("path.state", Directories.STATES.getPath());
        String server = getProperty("dataSources.defaultServer");
        if (server == null || DataSources.getServerSetting(server, "API.getDataSources") == null)
            setProperty("dataSources.defaultServer", "IAS");
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

}
