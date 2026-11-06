package org.helioviewer.jhv.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The 3D models the 3D Model Manager offers: read from {@code resources/data/assets.json}, fetched
 * on request into {@code ~/HFStudio/Models}, kept only if they match the list.
 *
 * <p>Nothing is bundled. Each entry is pinned to one commit of its source with a length and a
 * SHA-256, so what arrives is exactly the file whose size and licence were checked when the entry
 * was written. A download goes to a {@code .part} file of its own and is renamed into place only
 * once both match; a file already sitting under the final name that does not match is moved aside,
 * never overwritten or deleted.
 */
public final class ModelAssets {

    public static final String REGISTRY = "/data/assets.json";

    /** Where an entry's size came from: the page, the words on it, and what was assumed in using it. */
    public record Size(@Nullable Double meters, @Nullable String source, @Nullable String quote, String note) {}

    public record Entry(String id, String name, String url, String page, String sha256, long bytes, double extent,
                        @Nullable String target, @Nullable String targetNote, @Nullable Double metersPerUnit, Size size,
                        String credit, String sourceName, String terms) {

        /** The file name under the models folder: the id and the start of the hash, so a new version never collides with an old one. */
        public String fileName() {
            return id + '-' + sha256.substring(0, 12) + ".glb";
        }
    }

    private ModelAssets() {}

    public static List<Entry> entries() throws IOException {
        try (InputStream in = ModelAssets.class.getResourceAsStream(REGISTRY)) {
            if (in == null)
                throw new IOException("The model list " + REGISTRY + " is missing from this build");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static List<Entry> parse(String json) {
        JSONObject root = new JSONObject(json);
        JSONObject sources = root.getJSONObject("sources");
        JSONArray models = root.getJSONArray("models");
        List<Entry> out = new ArrayList<>(models.length());
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            JSONObject src = sources.getJSONObject(m.getString("source"));
            JSONObject s = m.getJSONObject("size");
            Size size = new Size(s.isNull("meters") ? null : s.getDouble("meters"), s.optString("source", null), s.optString("quote", null), s.getString("note"));
            out.add(new Entry(m.getString("id"), m.getString("name"), m.getString("url"), m.getString("page"), m.getString("sha256").toLowerCase(java.util.Locale.ROOT),
                    m.getLong("bytes"), m.getDouble("extent"), m.isNull("target") ? null : m.getString("target"), m.optString("targetNote", null),
                    m.isNull("metersPerUnit") ? null : m.getDouble("metersPerUnit"), size, m.getString("credit"), src.getString("name"), src.getString("terms")));
        }
        return out;
    }

    public static Path folder() {
        return Directories.MODELS.getFile().toPath();
    }

    public static Path file(Entry e) {
        return folder().resolve(e.fileName());
    }

    /** On disk under its name with the listed length. The hash was checked when it arrived. */
    public static boolean isPresent(Entry e) {
        Path p = file(e);
        try {
            return Files.isRegularFile(p) && Files.size(p) == e.bytes();
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Fetch the entry into the models folder and return its path; a file already there and
     * matching is returned as it is.
     */
    public static Path download(Entry e, LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        Path dir = folder();
        Files.createDirectories(dir);
        Path target = file(e);
        if (Files.isRegularFile(target)) {
            try {
                verify(Files.size(target), sha256(target), e);
                return target;
            } catch (IOException mismatch) {
                Path aside = dir.resolve(e.fileName() + ".unverified-" + System.currentTimeMillis());
                Files.move(target, aside);
                Log.warn("Model file " + target + " did not match its listing (" + mismatch.getMessage() + "); moved to " + aside);
            }
        }

        URI uri;
        try {
            uri = new URI(e.url());
        } catch (URISyntaxException ex) {
            throw new IOException("Bad address in the model list for " + e.name() + ": " + e.url(), ex);
        }
        Path part = Files.createTempFile(dir, e.id() + ".", ".part");
        boolean finished = false;
        try {
            MessageDigest sha = digest();
            long got = 0;
            try (NetClient nc = NetClient.of(uri, false, NetClient.NetCache.BYPASS);
                 InputStream in = nc.getStream();
                 OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled.getAsBoolean())
                        throw new CancellationException();
                    got += n;
                    if (got > e.bytes())
                        throw new IOException("the server sent more than the " + e.bytes() + " bytes the model list gives");
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    progress.accept(got);
                }
            }
            verify(got, HexFormat.of().formatHex(sha.digest()), e);
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            finished = true;
            return target;
        } finally {
            if (!finished)
                try {
                    Files.deleteIfExists(part); // only the partial file this call created
                } catch (IOException ex) {
                    Log.warn("Could not remove the partial download " + part, ex);
                }
        }
    }

    /** Throws unless both the length and the SHA-256 match the listing. */
    static void verify(long length, String sha256, Entry e) throws IOException {
        if (length != e.bytes())
            throw new IOException("the download is " + length + " bytes, the model list gives " + e.bytes());
        if (!e.sha256().equalsIgnoreCase(sha256))
            throw new IOException("the download's SHA-256 is " + sha256 + ", the model list gives " + e.sha256());
    }

    static String sha256(Path p) throws IOException {
        MessageDigest sha = digest();
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0)
                sha.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is missing from this Java", ex); // every Java SE has it
        }
    }

}
