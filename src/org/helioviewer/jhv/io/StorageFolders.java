package org.helioviewer.jhv.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

/**
 * Where the two big folders live: the exports and the persistent download cache.
 *
 * <p>Both default to a folder under {@code ~/HFStudio}. A person with a small internal disk can point
 * either at another drive; the choice is kept in {@code ~/HFStudio/storage.properties}, a file of
 * its own rather than a Settings key because Directories is asked for these paths before Settings has
 * been read (HFStudio.main creates the folders first), and because Settings and the log stay on the
 * internal disk whatever is chosen here.
 *
 * <p>A chosen folder that is missing or not writable (an external drive that is not plugged in) is
 * never fatal: the default folder is used for that session and {@link #note} says so. The choice is
 * kept, so the next launch with the drive connected goes back to it.
 */
public final class StorageFolders {

    public static final String EXPORTS = "exports";
    public static final String FILECACHE = "filecache";

    private static final String FILE_NAME = "storage.properties";

    private record State(Properties chosen, String note) {}

    private static final AtomicReference<State> STATE = new AtomicReference<>();

    /** The explanation of the last fallback to a default folder, or null. Shown in Settings and logged. */
    @Nullable
    public static String note() {
        State s = STATE.get();
        return s == null || s.note().isEmpty() ? null : s.note();
    }

    /** The folder for {@code key}: the chosen one when usable, else {@code defaultDir} (with a trailing separator). */
    public static String resolve(String key, String defaultDir) {
        String chosen = chosen(key);
        if (chosen == null)
            return defaultDir;
        File dir = new File(chosen);
        if ((dir.isDirectory() || dir.mkdirs()) && dir.canWrite())
            return withSeparator(chosen);
        remember(key + " folder " + chosen + " is not available (is the drive connected?); using " + defaultDir + " for now.");
        return defaultDir;
    }

    /** The folder the person chose for {@code key}, usable or not; null when none. */
    @Nullable
    public static String chosen(String key) {
        String v = state().chosen().getProperty(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Choose {@code dir} for {@code key}, or go back to the default with null. Takes effect at once. */
    public static void choose(String key, @Nullable String dir) throws IOException {
        Properties p = new Properties();
        p.putAll(state().chosen());
        if (dir == null || dir.isBlank())
            p.remove(key);
        else
            p.setProperty(key, dir.trim());
        Path file = file();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "HelioFITS Studio storage folders; missing folders fall back to the defaults");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        STATE.set(new State(p, ""));
    }

    private static State state() {
        State s = STATE.get();
        if (s != null)
            return s;
        Properties p = new Properties();
        Path file = file();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException | IllegalArgumentException e) {
                p.clear(); // an unreadable file means the defaults, never a failed launch
            }
        }
        STATE.compareAndSet(null, new State(p, ""));
        return STATE.get();
    }

    private static void remember(String note) {
        State s = state();
        STATE.set(new State(s.chosen(), note));
    }

    private static Path file() {
        return Path.of(Directories.HOME.getPath(), FILE_NAME);
    }

    /** {@code path} without a trailing separator, to compare a chosen folder with a resolved one. */
    public static String stripSeparator(String path) {
        return path.length() > 1 && path.endsWith(File.separator) ? path.substring(0, path.length() - 1) : path;
    }

    private static String withSeparator(String path) {
        return path.endsWith(File.separator) ? path : path + File.separator;
    }

    /** Forget what was read, so a check can point user.home elsewhere. */
    static void reset() {
        STATE.set(null);
    }

    private StorageFolders() {}
}
