package org.helioviewer.jhv.io;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.annotation.Nonnull;

import org.helioviewer.jhv.app.Log;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

public class NetFileCache {

    /** For every caller that is not reporting progress, which is most of them. */
    private static final java.util.function.LongConsumer NO_PROGRESS = bytes -> {};

    private static final LoadingCache<URI, DataUri> cache = Caffeine.newBuilder().softValues().
            build(uri -> fetch(uri, NO_PROGRESS));

    // Download temp files that a process which died mid-download (a quit, a kill, a crash) left
    // behind, swept on first use of the cache. Only old ones: a second running instance's download
    // keeps its temp file's mtime fresh, and stalls past the 60 s read timeout and fails well
    // before an hour, so an hour without a write means nobody is writing it.
    // ponytail: once per launch, so an orphan younger than the cutoff waits for a later launch.
    private static final long ORPHAN_AGE_MS = 3600_000L;
    private static final long COPY_CHUNK = 1 << 20;

    static {
        long cutoff = System.currentTimeMillis() - ORPHAN_AGE_MS;
        File[] orphans = Directories.FILECACHE.getFile().listFiles(f ->
                f.getName().startsWith("dl") && f.getName().endsWith(".tmp") && f.isFile() && f.lastModified() < cutoff);
        int swept = 0;
        if (orphans != null)
            for (File f : orphans)
                if (Directories.isInsideCache(f) && f.delete())
                    swept++;
        if (swept > 0)
            Log.info("Swept " + swept + " abandoned download temp file(s) from " + Directories.FILECACHE.getFile());
    }

    private static DataUri fetch(URI uri, java.util.function.LongConsumer onBytes) throws IOException {
        String scheme = uri.getScheme().toLowerCase();
        if ("jpip".equals(scheme) || "jpips".equals(scheme))
            return new DataUri(uri, uri, null);
        if ("file".equals(scheme)) {
            File file = new File(uri.getPath()); // for files with authority (//localhost) and Windows
            return new DataUri(uri, uri, file);
        }

        // Persistent content-addressed cache, keyed by the remote URI: a saved session reloads
        // from disk instead of re-downloading every launch (the app's main iteration cost).
        // ponytail: no eviction — a research tool re-visits the same datasets; add an LRU/size
        // cap (in Settings) if the FileCache dir ever grows past what the disk can spare.
        File cached = persistentPath(uri);
        if (cached.isFile() && cached.length() > 0)
            return new DataUri(uri, cached.toURI(), cached);

        // Download to a sibling temp file, then atomically publish, so a killed download never
        // leaves a truncated file that a later launch would mistake for a complete one.
        Path dir = Directories.FILECACHE.getFile().toPath();
        Path tmp = Files.createTempFile(dir, "dl", null);
        try {
            try (NetClient nc = NetClient.of(uri, false, NetClient.NetCache.BYPASS); BufferedSink sink = Okio.buffer(Okio.sink(tmp))) {
                // A chunked copy rather than sink.writeAll, which is one opaque call for a file
                // that can take minutes: these reports are the only thing that can say it is alive.
                BufferedSource source = nc.getSource();
                long read;
                while ((read = source.read(sink.getBuffer(), COPY_CHUNK)) != -1) {
                    sink.emitCompleteSegments();
                    onBytes.accept(read);
                }
            }

            Path target = cached.toPath();
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); // ATOMIC_MOVE unsupported across devices
            }
            return new DataUri(uri, target.toUri(), cached);
        } finally {
            // Whatever ended the download, not only an IOException; nothing to do once it was moved.
            // File.delete rather than Files.deleteIfExists, which could throw over the real failure.
            // A JVM that exits mid-download never gets here: the sweep above covers that.
            tmp.toFile().delete();
        }
    }

    /** Where this URI's bytes live on disk once cached, whether or not the file exists yet. */
    public static File cachedFile(@Nonnull URI uri) {
        return persistentPath(uri);
    }

    private static File persistentPath(URI uri) {
        return new File(Directories.FILECACHE.getFile(), sha256(uri.toString()));
    }

    private static String sha256(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash)
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // guaranteed by the JLS
        }
    }

    public static DataUri get(@Nonnull URI uri) throws IOException {
        return get(uri, NO_PROGRESS);
    }

    /**
     * @param onBytes told about each chunk of this URI as it lands off the network.
     *
     * <p>Per caller rather than a global counter, because two layers loading at once would
     * otherwise each report the other's bytes as their own. A URI already in the cache, or one
     * another thread is fetching, reports nothing: no bytes are crossing the wire for this
     * caller, which is what the readout is about.
     */
    public static DataUri get(@Nonnull URI uri, @Nonnull java.util.function.LongConsumer onBytes) throws IOException {
        try {
            return cache.get(uri, key -> {
                try {
                    return fetch(key, onBytes);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

}
