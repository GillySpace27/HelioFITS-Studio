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
 * The 3D models the 3D Model Manager offers: the list in {@code resources/data/assets.json}, shipped
 * inside Studio under {@code resources/models/}, plus whatever the user imports.
 *
 * <p>Each listed entry is pinned to one commit of its source with a length and a SHA-256, so the
 * bundled file is exactly the one whose size and licence were checked when the entry was written;
 * ModelAssetsCheck holds every bundled file to its hash. A listed model is copied out of Studio into
 * {@code ~/HFStudio/Models} the first time it is used, checked again on the way. A listed model not
 * bundled in a build can still be downloaded by the same checks. A download or copy goes to a
 * {@code .part} file of its own and is renamed into place only once both match; a file already
 * sitting under the final name that does not match is moved aside, never overwritten or deleted.
 *
 * <p>Imports (a local .glb or .gltf, or a .glb from any address) are listed in
 * {@code Models/imported.json}. They carry no checked size or position: the layer's options set those.
 */
public final class ModelAssets {

    public static final String REGISTRY = "/data/assets.json";
    /** Where the listed models ship inside Studio, under their {@link Entry#fileName()}. */
    static final String BUNDLED = "/models/";
    private static final long MAX_IMPORT = 500_000_000L;

    /** Where an entry's size came from: the page, the words on it, and what was assumed in using it. */
    public record Size(@Nullable Double meters, @Nullable String source, @Nullable String quote, String note) {}

    /** LISTED: from the model list, shipped inside Studio or fetched by checksum. IMPORTED: a file the user added. */
    public enum Origin {LISTED, IMPORTED}

    public record Entry(String id, String name, String url, String page, String sha256, long bytes, double extent,
                        @Nullable String target, @Nullable String targetNote, @Nullable Double metersPerUnit, Size size,
                        String credit, String sourceName, String terms, Origin origin) {

        /** The file name under the models folder: the id and the start of the hash, so a new version never collides with an old one. */
        public String fileName() {
            return id + '-' + sha256.substring(0, 12) + ".glb";
        }
    }

    private ModelAssets() {}

    /** The model list, then the user's imports. */
    public static List<Entry> entries() throws IOException {
        List<Entry> all = new ArrayList<>(listed());
        all.addAll(imported());
        return all;
    }

    public static List<Entry> listed() throws IOException {
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
                    m.isNull("metersPerUnit") ? null : m.getDouble("metersPerUnit"), size, m.getString("credit"), src.getString("name"), src.getString("terms"), Origin.LISTED));
        }
        return out;
    }

    public static Path folder() {
        return Directories.MODELS.getFile().toPath();
    }

    /** Where the model is, or will be, on disk. */
    public static Path file(Entry e) {
        return e.origin() == Origin.IMPORTED ? Path.of(URI.create(e.url())) : folder().resolve(e.fileName());
    }

    /** Shipped inside Studio: the eleven NASA models are, so they work offline from the first launch. */
    public static boolean isBundled(Entry e) {
        return e.origin() == Origin.LISTED && ModelAssets.class.getResource(BUNDLED + e.fileName()) != null;
    }

    /** Whether the model is on disk under its name with the listed length. The hash was checked when it arrived. */
    public static boolean isDownloaded(Entry e) {
        Path p = file(e);
        try {
            return Files.isRegularFile(p) && (e.origin() == Origin.IMPORTED || Files.size(p) == e.bytes());
        } catch (IOException ex) {
            return false;
        }
    }

    /** Usable now without the network: bundled, downloaded or imported. */
    public static boolean isPresent(Entry e) {
        return isBundled(e) || isDownloaded(e);
    }

    /**
     * A file on disk to load the model from. A bundled model is copied out of Studio into the models
     * folder the first time, checked against its hash like a download. Off the event thread.
     */
    public static Path localPath(Entry e) throws IOException {
        if (isDownloaded(e) || !isBundled(e))
            return file(e);
        Path dir = folder();
        Files.createDirectories(dir);
        Path part = Files.createTempFile(dir, e.id() + ".", ".part");
        boolean finished = false;
        try {
            try (InputStream in = ModelAssets.class.getResourceAsStream(BUNDLED + e.fileName())) {
                if (in == null)
                    throw new IOException("The bundled model " + e.fileName() + " is missing from this build");
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            verify(Files.size(part), sha256(part), e);
            Files.move(part, file(e), StandardCopyOption.ATOMIC_MOVE);
            finished = true;
            return file(e);
        } finally {
            if (!finished)
                try {
                    Files.deleteIfExists(part); // only the partial file this call created
                } catch (IOException ex) {
                    Log.warn("Could not remove the partial copy " + part, ex);
                }
        }
    }

    // Imports: the user's own files, or files fetched from an address they give. Listed in
    // Models/imported.json; a .glb is copied into Models/imported, a .gltf is used where it is
    // (its buffers and textures sit beside it). Removing one from the list never deletes a file.

    private static Path importedList() {
        return folder().resolve("imported.json");
    }

    public static List<Entry> imported() {
        Path list = importedList();
        List<Entry> out = new ArrayList<>();
        if (!Files.isRegularFile(list))
            return out;
        try {
            JSONArray a = new JSONArray(Files.readString(list));
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.getJSONObject(i);
                out.add(importedEntry(m.getString("name"), m.getString("url"), m.getString("from"), m.getString("sha256"), m.getLong("bytes")));
            }
        } catch (IOException | org.json.JSONException ex) {
            Log.warn("Could not read the imported model list " + list, ex);
        }
        return out;
    }

    private static Entry importedEntry(String name, String url, String from, String sha, long bytes) {
        return new Entry("imported-" + sha.substring(0, 12), name, url, from, sha, bytes, 1, null, null, null,
                new Size(null, null, null, "Imported: size not checked, so file units are taken as metres. Set Units and the object in the layer's options."),
                "Imported by you from " + from, "Imported", "Whatever terms came with the file.", Origin.IMPORTED);
    }

    /** Add a local .glb (copied into the models folder) or .gltf (used in place) to the imports. */
    public static Entry importFile(Path src, String name) throws IOException {
        if (!Files.isRegularFile(src))
            throw new IOException(src + " is not a file");
        String lower = src.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        String sha = sha256(src);
        Path at = src;
        if (lower.endsWith(".glb") || lower.endsWith(".glb.gz")) {
            Path dir = folder().resolve("imported");
            Files.createDirectories(dir);
            at = dir.resolve(sha.substring(0, 12) + '-' + src.getFileName());
            if (!Files.exists(at))
                Files.copy(src, at);
        }
        return addImport(name, at, src.toString(), sha, Files.size(src));
    }

    /** Fetch a model from an address into the models folder and add it to the imports. Off the event thread. */
    public static Entry importUrl(URI uri, String name, LongConsumer progress, BooleanSupplier cancelled) throws IOException {
        String file = Path.of(uri.getPath() == null || uri.getPath().isEmpty() ? "model.glb" : uri.getPath()).getFileName().toString();
        Path dir = folder().resolve("imported");
        Files.createDirectories(dir);
        Path part = Files.createTempFile(dir, "import.", ".part");
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
                    if (got > MAX_IMPORT)
                        throw new IOException("the file is larger than " + MAX_IMPORT / 1_000_000 + " MB");
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    progress.accept(got);
                }
            }
            String hex = HexFormat.of().formatHex(sha.digest());
            Path at = dir.resolve(hex.substring(0, 12) + '-' + file);
            if (Files.exists(at))
                Files.delete(part); // the same bytes are already here
            else
                Files.move(part, at, StandardCopyOption.ATOMIC_MOVE);
            finished = true;
            return addImport(name, at, uri.toString(), hex, got);
        } finally {
            if (!finished)
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ex) {
                    Log.warn("Could not remove the partial download " + part, ex);
                }
        }
    }

    private static synchronized Entry addImport(String name, Path at, String from, String sha, long bytes) throws IOException {
        Entry e = importedEntry(name, at.toUri().toString(), from, sha, bytes);
        JSONArray a = new JSONArray();
        for (Entry old : imported())
            if (!old.url().equals(e.url()))
                a.put(json(old));
        a.put(json(e));
        writeImported(a);
        return e;
    }

    /** Take an import off the list; the file stays where it is. */
    public static synchronized void removeImport(Entry e) throws IOException {
        JSONArray a = new JSONArray();
        for (Entry old : imported())
            if (!old.url().equals(e.url()))
                a.put(json(old));
        writeImported(a);
    }

    private static JSONObject json(Entry e) {
        return new JSONObject().put("name", e.name()).put("url", e.url()).put("from", e.page()).put("sha256", e.sha256()).put("bytes", e.bytes());
    }

    private static void writeImported(JSONArray a) throws IOException {
        Files.createDirectories(folder());
        Path tmp = Files.createTempFile(folder(), "imported.", ".json.part");
        Files.writeString(tmp, a.toString(2));
        Files.move(tmp, importedList(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
