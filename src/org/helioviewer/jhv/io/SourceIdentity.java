package org.helioviewer.jhv.io;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.thread.AppThread;

import org.json.JSONObject;

/**
 * Which file a local source frame is, by content, for an export's provenance.
 *
 * <p>A layer loaded from a local file (the FITS Library tab, a drop, -load) used to export with no
 * sources at all, because provenance keeps archive addresses only and never a local path. This
 * gives each such frame its identity instead: the SHA-256 of its bytes and, when the file is in a
 * FITS library, the library's {@code dataset_key}, {@code obs_key} and {@code dataset_uid}
 * (HelioFITS Core's own sha256 is read from its index, so a library file is never hashed). A file outside
 * any library is hashed here. Nothing returned ever holds a path.
 *
 * <p>The work is done when a layer loads ({@link #prefetch}), one file at a time on its own
 * background thread, and kept for the session keyed by path, size and modification time, so an
 * export reads a finished answer and a file changed on disk is identified again. {@link #lookup}
 * never blocks the caller unless it asks to wait.
 */
public final class SourceIdentity {

    /** The identity of one file: sha256 always, the HelioFITS Core fields when the library has the file. */
    public record Entry(String sha256, long size, @Nullable FitsLibrary.Identity library) {

        /** The provenance form: sha256, then dataset_key, obs_key and dataset_uid when known. */
        public JSONObject toJson() {
            JSONObject jo = new JSONObject().put("sha256", sha256);
            if (library != null) {
                jo.put("dataset_key", library.datasetKey());
                if (library.obsKey() != null)
                    jo.put("obs_key", library.obsKey());
                if (library.datasetUid() != null)
                    jo.put("dataset_uid", library.datasetUid());
            }
            return jo;
        }
    }

    private record Key(Path path, long size, long modified) {}

    private static final Map<Key, CompletableFuture<Entry>> known = new ConcurrentHashMap<>();
    private static final ExecutorService worker = AppThread.createIdleExecutor("HFS-SourceIdentity", 1);

    private SourceIdentity() {}

    /** Start identifying every local file among {@code uris}; other schemes are left alone. Returns at once. */
    public static void prefetch(List<URI> uris) {
        List<URI> copy = List.copyOf(uris);
        worker.execute(() -> copy.forEach(SourceIdentity::future)); // even the stat calls stay off the caller's thread
    }

    /**
     * The identity of a local file, or null: not a file: URI, not readable, or (with
     * {@code waitMs} 0) not finished yet. A positive {@code waitMs} waits that long for one that
     * is still being worked out; it is for export code running off the event thread.
     */
    @Nullable
    public static Entry lookup(URI uri, long waitMs) {
        CompletableFuture<Entry> f = future(uri);
        if (f == null)
            return null;
        try {
            return waitMs <= 0 ? f.getNow(null) : f.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException | java.util.concurrent.CompletionException e) {
            return null;
        }
    }

    @Nullable
    private static CompletableFuture<Entry> future(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme()))
            return null;
        Key key;
        try {
            Path path = Path.of(uri).toAbsolutePath().normalize();
            key = new Key(path, Files.size(path), Files.getLastModifiedTime(path).toMillis());
        } catch (IOException | RuntimeException e) { // not a regular readable file, or a URI Path cannot take
            return null;
        }
        return known.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> identify(k), worker));
    }

    private static Entry identify(Key key) {
        FitsLibrary.Identity library = library(key.path(), FitsLibrary.find());
        if (library != null && library.sha256() != null)
            return new Entry(library.sha256(), key.size(), library);
        try {
            return new Entry(sha256(key.path()), key.size(), null);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The library's record of a file under its root, or null: no library, outside it, not current, or unreadable. */
    @Nullable
    static FitsLibrary.Identity library(Path file, @Nullable FitsLibrary.Location lib) {
        if (lib == null)
            return null;
        Path root = lib.root().toAbsolutePath().normalize();
        if (!file.startsWith(root))
            return null;
        String relpath = root.relativize(file).toString().replace('\\', '/');
        try {
            return FitsLibrary.identity(lib, relpath);
        } catch (SQLException | FitsLibrary.TooNew e) {
            Log.warn("FITS library index not read for provenance: " + e.getMessage());
            return null;
        }
    }

    /** The file's SHA-256, lowercase hex. */
    public static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java platform must have SHA-256
        }
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buf)) > 0; )
                md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

}
