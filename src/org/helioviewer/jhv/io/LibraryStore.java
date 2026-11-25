package org.helioviewer.jhv.io;

import java.awt.EventQueue;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.app.Settings;

/**
 * The FITS library as Studio's one store for downloaded FITS (Gilly, 2026-11-07, option A).
 *
 * <p>When a FITS library is reachable and its HelioFITS Core takes {@code ingest.path}'s
 * {@code consume} (pyHelioFITS schema 6), a FITS download is moved into the library instead of
 * being kept in {@code FileCache} as well, and FileCache holds only downloads in flight, files that
 * are not FITS, and files that arrived while no library could take them. Without a library, or with
 * an older core, nothing changes: FileCache is the store, as before.
 *
 * <p>Finding a file again needs no running core. Two ways, first that answers:
 * <ol>
 * <li>{@code FileCache/library-moves.tsv}: one line per file Studio moved, its FileCache name (a pure
 * function of the URL, {@link NetFileCache}), its path in the library and the library's root, so a
 * line about another library (one used before a change of folder) is never followed. This covers
 * files moved from an old cache, whose URL Studio no longer knows.</li>
 * <li>The library index read-only, {@code file_sources} by URL ({@link FitsLibrary#bySource}),
 * for files that came into the library some other way.</li>
 * </ol>
 * A hit whose file is gone (deleted from the library, a drive not connected) is a miss, and the file
 * downloads again.
 *
 * <p>On macOS, where Studio carries its own HelioFITS Core, the first FITS download on a computer
 * with no library makes one at {@code ~/HelioFITSLibrary} ({@link #autoCreate}), once, and says so.
 */
public final class LibraryStore {

    static final String MOVES = "library-moves.tsv";

    /** Where a moved file went: its path in the library, and that library's root. */
    private record Move(String relpath, String root) {}

    private record Moves(Path file, Map<String, Move> byName) {}

    private static final AtomicReference<Moves> MOVES_READ = new AtomicReference<>();

    /** Set once Studio has made a library on its own ({@link #autoCreate}), so it never does that twice. */
    static final String AUTO_KEY = "fitslibrary.autoCreated";
    private static final AtomicBoolean AUTO_TRIED = new AtomicBoolean();

    /** The library's copy of what {@code uri} delivered, or null. Never starts a core. */
    @Nullable
    public static File lookup(URI uri, List<String> cacheNames) {
        FitsLibrary.Location lib = FitsLibrary.find();
        if (lib == null || !Files.isDirectory(lib.root()))
            return null;
        Map<String, Move> moved = moves().byName();
        String root = lib.root().toAbsolutePath().toString();
        for (String name : cacheNames) {
            File f = resolve(lib, root, moved.get(name));
            if (f != null)
                return f;
        }
        for (String name : cacheNames) { // then what the other installs on this computer moved (read-only)
            File f = resolve(lib, root, siblingMoves().get(name));
            if (f != null)
                return f;
        }
        String url = FitsCoreIngest.provenance(uri);
        return url == null ? null : FitsLibrary.bySource(lib, url);
    }

    @Nullable
    private static File resolve(FitsLibrary.Location lib, String root, @Nullable Move m) {
        if (m == null || !m.root().equals(root))
            return null;
        File f = lib.root().resolve(m.relpath()).toFile();
        return f.isFile() ? f : null;
    }

    /** Sibling installs' moves, re-read when a file's size or time changes. */
    private static final Map<Path, Object[]> SIBLING_READ = new ConcurrentHashMap<>();

    private static Map<String, Move> siblingMoves() {
        Map<String, Move> all = new java.util.HashMap<>();
        for (Path home : Siblings.homes()) {
            Path file = home.resolve("FileCache").resolve(MOVES);
            try {
                String stamp = Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
                Object[] cached = SIBLING_READ.get(file);
                if (cached == null || !cached[0].equals(stamp)) {
                    cached = new Object[]{stamp, readMoves(file)};
                    SIBLING_READ.put(file, cached);
                }
                @SuppressWarnings("unchecked")
                Map<String, Move> m = (Map<String, Move>) cached[1];
                all.putAll(m);
            } catch (IOException e) {
                // no such file: that install has moved nothing
            }
        }
        return all;
    }

    /** Copy the other installs' move lines for this library into this install's own list; how many were new. */
    public static int importMoves() {
        FitsLibrary.Location lib = FitsLibrary.find();
        if (lib == null)
            return 0;
        String root = lib.root().toAbsolutePath().toString();
        Moves own = moves();
        int added = 0;
        for (Map.Entry<String, Move> e : siblingMoves().entrySet()) {
            Move m = e.getValue();
            if (m.root().equals(root) && !own.byName().containsKey(e.getKey())) {
                remember(lib.root(), e.getKey(), m.relpath());
                added++;
            }
        }
        return added;
    }

    /**
     * Move a FITS file from FileCache into the library and return where it is now; null when it stays
     * where it is (not FITS, no reachable library, no core, an older core). Blocks the calling thread
     * (a download thread) while the core checksums and moves the file.
     */
    @Nullable
    public static File adopt(@Nullable URI uri, File file) {
        FitsLibrary.Location lib = FitsLibrary.find();
        if (lib == null)
            lib = autoCreate(file, Platform.isMacOS());
        if (lib == null || !Files.isDirectory(lib.root()))
            return null;
        String rel = FitsCoreIngest.consume(lib.root(), uri, file);
        if (rel == null)
            return null;
        remember(lib.root(), file.getName(), rel);
        File stored = lib.root().resolve(rel).toFile();
        return stored.isFile() ? stored : null;
    }

    /**
     * Make a FITS library at HelioFITS Core's default folder ({@code ~/HelioFITSLibrary}) for the
     * first FITS download on a computer that has none, and say so once; null when it does not
     * apply. It applies only on macOS (where Studio carries HelioFITS Core), only once per
     * computer, only where HelioFITS Core has no config at all (one that names a library on a
     * drive not connected now is never rewritten), only into an absent or empty folder, and only
     * with a core to run. Blocks while the core makes the library.
     *
     * @param mac whether this is macOS, a parameter so a check can run it anywhere
     */
    @Nullable
    static FitsLibrary.Location autoCreate(File file, boolean mac) {
        if (!mac || Settings.isSet(AUTO_KEY) || !FitsCoreIngest.isFits(file)
                || Files.exists(FitsLibrary.configDir().resolve("config.json")))
            return null;
        Path root = FitsLibrary.defaultRoot();
        if (!FitsLibrary.canMakeLibraryAt(root) || !FitsCoreIngest.coreFound(root) || !AUTO_TRIED.compareAndSet(false, true))
            return null;
        EventQueue.invokeLater(() -> Settings.setProperty(AUTO_KEY, root.toString())); // Settings is written on the EDT
        try {
            FitsCoreIngest.makeLibrary(root);
        } catch (IOException e) {
            Log.warn("Could not make a FITS library at " + root + "; FITS downloads stay in Studio's cache", e);
            return null;
        }
        FitsLibrary.Location lib = FitsLibrary.find();
        if (lib != null) {
            Log.info("Made the FITS library at " + root);
            EventQueue.invokeLater(() -> Message.info("FITS library", "Studio made a FITS library at " + root
                    + " and keeps FITS downloads there, one copy each.\n\nSettings > FITS Library can put it in another folder or on another drive."));
        }
        return lib;
    }

    /** Record that the FileCache file {@code name} now lives at {@code relpath} in the library at root. */
    static void remember(Path root, String name, String relpath) {
        Moves m = moves();
        String rootText = root.toAbsolutePath().toString();
        m.byName().put(name, new Move(relpath, rootText));
        synchronized (LibraryStore.class) {
            try (Writer w = Files.newBufferedWriter(m.file(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(name + '\t' + relpath + '\t' + rootText + '\n');
            } catch (IOException e) {
                Log.warn("Could not record where " + name + " went in the FITS library", e); // the index by URL still finds it
            }
        }
    }

    private static Moves moves() {
        Path file = Directories.FILECACHE.getFile().toPath().resolve(MOVES);
        Moves m = MOVES_READ.get();
        if (m != null && m.file().equals(file))
            return m;
        Moves fresh = new Moves(file, readMoves(file));
        MOVES_READ.set(fresh);
        return fresh;
    }

    /** One list of moves; a line that does not parse (a half-written last line, say) is skipped. */
    private static Map<String, Move> readMoves(Path file) {
        Map<String, Move> byName = new ConcurrentHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t", -1); // name, relpath, library root
                    if (parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty())
                        byName.put(parts[0], new Move(parts[1], parts[2])); // a later line wins
                }
            } catch (IOException e) {
                Log.warn("Could not read " + file, e);
            }
        }
        return byName;
    }

    /** Forget what was read and tried, for a check that points the cache or the home elsewhere. */
    static void reset() {
        MOVES_READ.set(null);
        SIBLING_READ.clear();
        AUTO_TRIED.set(false);
    }

    private LibraryStore() {}
}
