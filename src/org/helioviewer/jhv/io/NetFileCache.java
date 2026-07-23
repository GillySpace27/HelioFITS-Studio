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

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import okio.BufferedSink;
import okio.Okio;

public class NetFileCache {

    private static final LoadingCache<URI, DataUri> cache = Caffeine.newBuilder().softValues().
            build(NetFileCache::fetch);

    private static DataUri fetch(URI uri) throws IOException {
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
        try (NetClient nc = NetClient.of(uri, false, NetClient.NetCache.BYPASS); BufferedSink sink = Okio.buffer(Okio.sink(tmp))) {
            sink.writeAll(nc.getSource());
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        Path target = cached.toPath();
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); // ATOMIC_MOVE unsupported across devices
        }
        return new DataUri(uri, target.toUri(), cached);
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
        try {
            return cache.get(uri);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

}
