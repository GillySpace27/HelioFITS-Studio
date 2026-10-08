package org.helioviewer.jhv.app.state;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.AppInfo;
import org.helioviewer.jhv.app.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A session's local data, packed into one archive that travels beside the .jhv.
 *
 * <p>A session file records where its data came from. For an archive layer that is enough: another
 * machine asks the archive again. For a layer read from the person's own disk (proprietary data, a
 * reduction nobody has published) the record is a path that exists on one machine only, and the
 * layer silently fails to restore anywhere else. This packs exactly those files.
 *
 * <p>The format is a plain zip, chosen over tar because the standard library writes and reads it
 * with no dependency, because every desktop opens one without installing anything (Windows
 * included, and without admin rights), because its central directory lets a reader reach one entry
 * without streaming the rest, and because ZIP64 lifts the 4 GB limit. Inside:
 *
 * <ul>
 * <li>{@code data/NNNN/<name>}: each local file, under its own numbered folder so two files with
 * the same name from different folders cannot collide. Already-compressed formats are stored, the
 * rest are deflated at the fastest level.
 * <li>{@code session.jhv}: the session itself, so the archive alone is enough to open the scene.
 * <li>{@code manifest.json}: {@value #FORMAT} version {@value #VERSION}, an archive id, and for each
 * file its original URI, size and SHA-256. Reattaching matches on the URI and refuses a file whose
 * digest does not match, so a damaged or swapped archive cannot feed a layer the wrong data.
 * </ul>
 *
 * <p>Every write is atomic: the archive is written to a temporary file in the same folder, forced
 * to disk and moved into place, then the .jhv the same way. A crash or a full disk mid-export
 * leaves the previous files untouched rather than a truncated zip with a plausible name. Unpacking
 * is atomic the same way: a temporary folder, every digest verified, then one rename.
 */
public final class SessionArchive {

    public static final String FORMAT = "hfstudio-session-data";
    public static final int VERSION = 1;
    /** Appended to the session's base name: {@code talk.jhv} travels with {@code talk.data.zip}. */
    public static final String SUFFIX = ".data.zip";
    /** Key in the exported session naming its archive, beside the layers. Older readers ignore it. */
    public static final String SESSION_KEY = "sidecar";

    static final String MANIFEST = "manifest.json";
    static final String SESSION = "session.jhv";
    private static final String STATE_KEY = "org.helioviewer.jhv.state";
    private static final String COMPLETE = ".complete";
    private static final Pattern DATA_ENTRY = Pattern.compile("data/[0-9]{4,}/[A-Za-z0-9._+-]+");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final List<String> COMPRESSED = List.of(".jp2", ".jpx", ".j2k", ".fz", ".gz", ".bz2", ".zip",
            ".png", ".jpg", ".jpeg", ".mp4", ".mov");

    /** What an export wrote. {@code archive} is null when the session had no local file to pack. */
    public record Export(Path session, @Nullable Path archive, int files, long bytes) {}

    /** What a load found: local files the session names that are not on this machine, and how many the archive supplied. */
    public record Reattach(int missing, int restored, @Nullable Path archive) {}

    /** The archive name for a session file name. */
    public static String archiveName(String sessionFile) {
        String base = sessionFile.toLowerCase(Locale.ROOT).endsWith(".jhv")
                ? sessionFile.substring(0, sessionFile.length() - 4) : sessionFile;
        return base + SUFFIX;
    }

    /**
     * The local files a session reads, in layer order, each once: every file: URI in an image
     * layer's list that names a regular file on this machine. Remote URIs are left out on purpose:
     * another machine fetches those itself, which keeps the archive to what cannot be fetched.
     */
    public static Map<URI, Path> localFiles(JSONObject state) {
        Map<URI, Path> out = new LinkedHashMap<>();
        forEachUri(state, uri -> {
            Path p = localPath(uri);
            if (p != null && Files.isRegularFile(p))
                out.putIfAbsent(uri, p);
            return null;
        });
        return out;
    }

    /**
     * Write {@code session} (as {@link State#snapshot()} returns it) to {@code sessionPath} and its
     * local files to the archive beside it. With no local file only the .jhv is written.
     */
    public static Export export(JSONObject session, Path sessionPath) throws IOException {
        JSONObject wrapped = new JSONObject(session.toString()); // the caller's snapshot stays as it was
        JSONObject state = wrapped.getJSONObject(STATE_KEY);
        state.remove(SESSION_KEY); // a re-export of an imported session names its own archive, not the old one
        Map<URI, Path> files = localFiles(state);
        Path dir = sessionPath.toAbsolutePath().getParent();
        String name = sessionPath.getFileName().toString();
        if (files.isEmpty()) {
            writeAtomic(sessionPath, wrapped.toString(1).getBytes(StandardCharsets.UTF_8));
            return new Export(sessionPath, null, 0, 0);
        }

        String id = UUID.randomUUID().toString();
        Path archive = dir.resolve(archiveName(name));
        state.put(SESSION_KEY, new JSONObject().put("file", archive.getFileName().toString()).put("id", id));
        byte[] sessionBytes = wrapped.toString(1).getBytes(StandardCharsets.UTF_8);

        JSONArray entries = new JSONArray();
        long total = 0;
        Path temp = dir.resolve(archive.getFileName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                 ZipOutputStream zip = new ZipOutputStream(java.nio.channels.Channels.newOutputStream(channel))) {
                int index = 0;
                for (Map.Entry<URI, Path> e : files.entrySet()) {
                    index++;
                    Path file = e.getValue();
                    String entryName = String.format(Locale.ROOT, "data/%04d/%s", index, safeName(file.getFileName().toString()));
                    zip.setLevel(isCompressed(entryName) ? Deflater.NO_COMPRESSION : Deflater.BEST_SPEED);
                    zip.putNextEntry(new ZipEntry(entryName));
                    MessageDigest sha = sha256();
                    long size;
                    try (InputStream in = Files.newInputStream(file)) {
                        size = copy(in, zip, sha);
                    }
                    zip.closeEntry();
                    total += size;
                    entries.put(new JSONObject().put("path", entryName).put("uri", e.getKey().toString())
                            .put("size", size).put("sha256", HexFormat.of().formatHex(sha.digest())));
                }
                zip.setLevel(Deflater.DEFAULT_COMPRESSION);
                putBytes(zip, SESSION, sessionBytes);
                JSONObject manifest = new JSONObject().put("format", FORMAT).put("version", VERSION).put("id", id)
                        .put("created", Instant.now().toString())
                        .put("application", AppInfo.programName + " " + AppInfo.version)
                        .put("session", SESSION).put("files", entries);
                putBytes(zip, MANIFEST, manifest.toString(1).getBytes(StandardCharsets.UTF_8));
                zip.finish();
                channel.force(true); // on disk before the rename makes it visible under the real name
            }
            move(temp, archive);
        } finally {
            Files.deleteIfExists(temp);
        }
        // The session last: until it lands, whatever .jhv was there before still opens.
        writeAtomic(sessionPath, sessionBytes);
        return new Export(sessionPath, archive, files.size(), total);
    }

    /**
     * Point a session's missing local files at the copies in its archive, before the layers are
     * built. {@code state} is the inner state object and is rewritten in place; {@code archive} is
     * the archive itself when one was opened directly, else null to look beside {@code sessionPath}.
     * Files that exist here are left alone, so the machine that made the archive keeps reading its
     * originals. Unpacked data goes under {@code unpackRoot}, one folder per archive id, and is
     * reused on the next load.
     */
    public static Reattach reattach(JSONObject state, @Nullable Path sessionPath, @Nullable Path archive,
                                    Path unpackRoot) throws IOException {
        List<URI> missing = new ArrayList<>();
        forEachUri(state, uri -> {
            Path p = localPath(uri);
            if (p != null && !Files.isRegularFile(p) && !missing.contains(uri))
                missing.add(uri);
            return null;
        });
        if (missing.isEmpty())
            return new Reattach(0, 0, null);
        if (archive == null && sessionPath != null)
            archive = findArchive(state, sessionPath);
        if (archive == null)
            return new Reattach(missing.size(), 0, null);

        if (!listsAny(archive, missing)) // the archive cannot help, so do not unpack it
            return new Reattach(missing.size(), 0, archive);
        Map<URI, Path> unpacked = unpack(archive, unpackRoot);
        Map<URI, URI> replace = new HashMap<>();
        for (URI uri : missing) {
            Path p = unpacked.get(uri);
            if (p != null)
                replace.put(uri, p.toUri());
        }
        forEachUri(state, replace::get);
        Log.info("Session data archive " + archive.getFileName() + ": " + replace.size() + " of " + missing.size()
                + " missing local file(s) restored");
        return new Reattach(missing.size(), replace.size(), archive);
    }

    private static boolean listsAny(Path archive, List<URI> uris) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            JSONArray files = readManifest(zip).getJSONArray("files");
            for (int i = 0; i < files.length(); i++)
                if (uris.contains(URI.create(files.getJSONObject(i).getString("uri"))))
                    return true;
            return false;
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException(archive.getFileName() + ": unreadable manifest", e);
        }
    }

    /** The session inside an archive opened directly, as its inner state object. */
    public static JSONObject sessionIn(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            readManifest(zip); // refuse a zip that is not one of ours before reading anything else from it
            ZipEntry entry = zip.getEntry(SESSION);
            if (entry == null)
                throw new IOException(archive.getFileName() + " has no " + SESSION);
            try (InputStream in = zip.getInputStream(entry)) {
                return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONObject(STATE_KEY);
            } catch (JSONException e) {
                throw new IOException(archive.getFileName() + ": unreadable " + SESSION, e);
            }
        }
    }

    /** Whether a file name looks like a session data archive, for the open and drop paths. */
    public static boolean isArchiveName(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(SUFFIX);
    }

    @Nullable
    private static Path findArchive(JSONObject state, Path sessionPath) {
        Path dir = sessionPath.toAbsolutePath().getParent();
        if (dir == null)
            return null;
        JSONObject sidecar = state.optJSONObject(SESSION_KEY);
        String named = sidecar == null ? "" : sidecar.optString("file", "");
        // Only a bare file name, so a session from elsewhere cannot point the reader outside its own folder.
        if (!named.isEmpty() && named.equals(Path.of(named).getFileName().toString()) && Files.isRegularFile(dir.resolve(named)))
            return dir.resolve(named);
        Path conventional = dir.resolve(archiveName(sessionPath.getFileName().toString()));
        return Files.isRegularFile(conventional) ? conventional : null;
    }

    /**
     * Unpack every file the manifest lists, verifying each digest, into {@code root/<id>}, and map
     * each original URI to its unpacked file. All or nothing: one bad digest and nothing is kept.
     */
    static Map<URI, Path> unpack(Path archive, Path root) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            JSONObject manifest = readManifest(zip);
            String id = manifest.optString("id", "");
            if (!SAFE_ID.matcher(id).matches())
                id = HexFormat.of().formatHex(sha256().digest(manifest.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 32);
            Path target = root.resolve(id);
            JSONArray files = manifest.getJSONArray("files");

            if (!Files.isRegularFile(target.resolve(COMPLETE))) {
                Files.createDirectories(root);
                Path temp = Files.createTempDirectory(root, id + ".tmp-");
                try {
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject f = files.getJSONObject(i);
                        String path = f.getString("path");
                        ZipEntry entry = zip.getEntry(path);
                        if (entry == null)
                            throw new IOException(archive.getFileName() + " lists " + path + " but does not contain it");
                        Path out = temp.resolve(path);
                        Files.createDirectories(out.getParent());
                        MessageDigest sha = sha256();
                        long size;
                        try (InputStream in = zip.getInputStream(entry); OutputStream os = Files.newOutputStream(out)) {
                            size = copy(in, os, sha);
                        }
                        String digest = HexFormat.of().formatHex(sha.digest());
                        if (size != f.getLong("size") || !digest.equalsIgnoreCase(f.getString("sha256")))
                            throw new IOException(archive.getFileName() + ": " + path + " does not match its checksum; the archive is damaged");
                    }
                    Files.writeString(temp.resolve(COMPLETE), manifest.toString());
                    try {
                        move(temp, target);
                    } catch (FileAlreadyExistsException | java.nio.file.DirectoryNotEmptyException e) {
                        // Another load unpacked the same archive first. Use its copy if it finished.
                        if (!Files.isRegularFile(target.resolve(COMPLETE)))
                            throw e;
                    }
                } finally {
                    deleteTree(temp);
                }
            }

            Map<URI, Path> out = new HashMap<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                Path p = target.resolve(f.getString("path"));
                if (Files.isRegularFile(p))
                    out.put(URI.create(f.getString("uri")), p);
            }
            return out;
        } catch (JSONException e) {
            throw new IOException(archive.getFileName() + ": unreadable manifest", e);
        }
    }

    private static JSONObject readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(MANIFEST);
        if (entry == null)
            throw new IOException(Path.of(zip.getName()).getFileName() + " is not a session data archive (no " + MANIFEST + ")");
        JSONObject manifest;
        try (InputStream in = zip.getInputStream(entry)) {
            manifest = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("unreadable " + MANIFEST, e);
        }
        if (!FORMAT.equals(manifest.optString("format")))
            throw new IOException(Path.of(zip.getName()).getFileName() + " is not a session data archive");
        if (manifest.optInt("version", 0) > VERSION)
            throw new IOException(Path.of(zip.getName()).getFileName() + " was written by a newer version (format "
                    + manifest.optInt("version") + "); update HelioFITS Studio to open it");
        JSONArray files = manifest.optJSONArray("files");
        if (files == null)
            throw new IOException("manifest lists no files");
        // Entry names are ours to choose, so anything else (a path with .., an absolute path) is refused
        // here rather than resolved: the unpacker never writes outside its own folder.
        for (int i = 0; i < files.length(); i++)
            if (!DATA_ENTRY.matcher(files.getJSONObject(i).optString("path")).matches())
                throw new IOException("manifest entry with an unexpected path: " + files.getJSONObject(i).optString("path"));
        return manifest;
    }

    private interface UriEdit {
        @Nullable
        URI edit(URI uri);
    }

    // Visit every URI in every image layer's list; a non-null return replaces it in place.
    private static void forEachUri(JSONObject state, UriEdit edit) {
        JSONArray layers = state.optJSONArray("imageLayers");
        if (layers == null)
            return;
        for (int i = 0; i < layers.length(); i++) {
            JSONObject layer = layers.optJSONObject(i);
            JSONObject data = layer == null ? null : layer.optJSONObject("data");
            JSONArray uris = data == null ? null : data.optJSONArray("uris");
            if (uris == null)
                continue;
            for (int j = 0; j < uris.length(); j++) {
                URI uri;
                try {
                    uri = URI.create(uris.optString(j));
                } catch (IllegalArgumentException e) {
                    continue; // not ours to judge; the layer reports it when it loads
                }
                URI replaced = edit.edit(uri);
                if (replaced != null)
                    uris.put(j, replaced.toString());
            }
        }
    }

    @Nullable
    private static Path localPath(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme()))
            return null;
        try {
            return Path.of(uri);
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return null; // a file: URI from another OS (C:\ on a Mac) that this one cannot express
        }
    }

    static String safeName(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._+-]", "_");
        while (s.startsWith("."))
            s = s.substring(1);
        if (s.length() > 120) // keep the extension, which is how the reader picks a format
            s = s.substring(0, 100) + s.substring(s.length() - 20);
        return s.isEmpty() ? "file" : s;
    }

    private static boolean isCompressed(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String ext : COMPRESSED)
            if (n.endsWith(ext))
                return true;
        return false;
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static long copy(InputStream in, OutputStream out, MessageDigest sha) throws IOException {
        byte[] buf = new byte[1 << 16];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            sha.update(buf, 0, n);
            total += n;
        }
        return total;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM provides SHA-256", e);
        }
    }

    // Same pattern as State.writeJson: temp file in the target's folder, forced, then renamed over it.
    static void writeAtomic(Path path, byte[] bytes) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            move(temp, path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) { // a filesystem without atomic rename
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // Only ever called on a temporary folder this class created.
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir))
            return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.deleteIfExists(p);
        }
    }

    private SessionArchive() {}
}
