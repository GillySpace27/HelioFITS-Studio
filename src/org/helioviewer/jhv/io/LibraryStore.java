package org.helioviewer.jhv.io;

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
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;

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
 * function of the URL, {@link NetFileCache}) and its path in the library. This covers files moved
 * from an old cache, whose URL Studio no longer knows.</li>
 * <li>The library index read-only, {@code file_sources} by URL ({@link FitsLibrary#bySource}),
 * for files that came into the library some other way.</li>
 * </ol>
 * A hit whose file is gone (deleted from the library, a drive not connected) is a miss, and the file
 * downloads again.
 */
public final class LibraryStore {

    static final String MOVES = "library-moves.tsv";

    private record Moves(Path file, Map<String, String> byName) {}

    private static final AtomicReference<Moves> MOVES_READ = new AtomicReference<>();

    /** The library's copy of what {@code uri} delivered, or null. Never starts a core. */
    @Nullable
    public static File lookup(URI uri, List<String> cacheNames) {
        FitsLibrary.Location lib = FitsLibrary.find();
        if (lib == null || !Files.isDirectory(lib.root()))
            return null;
        Map<String, String> moved = moves().byName();
        for (String name : cacheNames) {
            String rel = moved.get(name);
            if (rel != null) {
                File f = lib.root().resolve(rel).toFile();
                if (f.isFile())
                    return f;
            }
        }
        String url = FitsCoreIngest.provenance(uri);
        return url == null ? null : FitsLibrary.bySource(lib, url);
    }

    /**
     * Move a FITS file from FileCache into the library and return where it is now; null when it stays
     * where it is (not FITS, no reachable library, no core, an older core). Blocks the calling thread
     * (a download thread) while the core checksums and moves the file.
     */
    @Nullable
    public static File adopt(@Nullable URI uri, File file) {
        FitsLibrary.Location lib = FitsLibrary.find();
        if (lib == null || !Files.isDirectory(lib.root()))
            return null;
        String rel = FitsCoreIngest.consume(lib.root(), uri, file);
        if (rel == null)
            return null;
        remember(file.getName(), rel);
        File stored = lib.root().resolve(rel).toFile();
        return stored.isFile() ? stored : null;
    }

    /** Record that the FileCache file {@code name} now lives at {@code relpath} in the library. */
    static void remember(String name, String relpath) {
        Moves m = moves();
        m.byName().put(name, relpath);
        synchronized (LibraryStore.class) {
            try (Writer w = Files.newBufferedWriter(m.file(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(name + '\t' + relpath + '\n');
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
        Map<String, String> byName = new ConcurrentHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int tab = line.indexOf('\t');
                    if (tab > 0 && tab < line.length() - 1)
                        byName.put(line.substring(0, tab), line.substring(tab + 1)); // a later line wins
                }
            } catch (IOException e) {
                Log.warn("Could not read " + file, e);
            }
        }
        Moves fresh = new Moves(file, byName);
        MOVES_READ.set(fresh);
        return fresh;
    }

    /** Forget what was read, for a check that points the cache elsewhere. */
    static void reset() {
        MOVES_READ.set(null);
    }

    private LibraryStore() {}
}
