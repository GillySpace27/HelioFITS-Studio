package org.helioviewer.jhv.app.state;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Local files a layer reads that are not on this computer, and where else to look for them.
 *
 * <p>A session records a layer read from disk as the file: URI it had on the machine that saved
 * it. Sent to someone else (Sarah Gibson, HAO, 2026-10, with KCor frames from Gilly's Downloads
 * folder), every one of those URIs fails: one dialog per file, then "Empty list of views" for a
 * layer whose every frame was missing. Whoever is sent a session is most often sent its files loose
 * beside it, so the first place to look is the session's own folder, by file name. The data archive
 * ({@link SessionArchive}) is the second.
 *
 * <p>Pure: whether a file exists is the caller's predicate, so the check runs without a disk.
 */
public final class MissingFiles {

    /** The path a file: URI names on this computer; null for another scheme or a path this OS cannot express. */
    public static Path localPath(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme()))
            return null;
        try {
            return Path.of(uri);
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return null; // a file: URI from another OS (C:\ on a Mac)
        }
    }

    /**
     * The last name in a file: URI, read off the URI itself rather than through {@link Path}, so a
     * Windows path is understood on a Mac and the other way round. Null when there is no usable
     * name: not a file: URI, a folder, or a name that could step out of the folder it is put in.
     */
    public static String fileName(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getPath() == null)
            return null;
        String p = uri.getPath();
        String name = p.substring(Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\')) + 1);
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0)
            return null;
        return name;
    }

    /** The file: URIs in {@code uris} whose file {@code exists} rejects, in order, each once. */
    public static List<URI> missing(List<URI> uris, Predicate<Path> exists) {
        List<URI> out = new ArrayList<>();
        for (URI uri : uris) {
            if (!"file".equalsIgnoreCase(uri.getScheme()) || out.contains(uri))
                continue;
            Path p = localPath(uri);
            if (p == null || !exists.test(p))
                out.add(uri);
        }
        return out;
    }

    /**
     * For each missing URI, the file of the same name in {@code dir} (the folder of the session
     * being opened), when {@code exists} accepts it. URIs with no such file are left out.
     */
    public static Map<URI, Path> besideSession(Collection<URI> missing, Path dir, Predicate<Path> exists) {
        Map<URI, Path> out = new LinkedHashMap<>();
        if (dir == null)
            return out;
        for (URI uri : missing) {
            String name = fileName(uri);
            if (name == null)
                continue;
            Path candidate;
            try {
                candidate = dir.resolve(name);
            } catch (InvalidPathException e) {
                continue; // a name this OS cannot hold
            }
            if (dir.equals(candidate.getParent()) && exists.test(candidate))
                out.put(uri, candidate);
        }
        return out;
    }

    /** Where a URI points, as the person who saved it would recognize it. */
    static String shown(URI uri) {
        return uri.getPath() == null ? uri.toString() : uri.getPath();
    }

    /**
     * The one message for a layer some of whose files are not here: how many, one example, why it
     * happens, and what fixes it.
     */
    public static String summary(int missing, int total, URI example) {
        String what;
        if (total <= 1)
            what = "The file this layer reads is not on this computer: " + shown(example) + ".";
        else if (missing >= total)
            what = "None of the " + total + " files this layer reads are on this computer, for example " + shown(example) + ".";
        else
            what = missing + " of the " + total + " files this layer reads are not on this computer, for example "
                    + shown(example) + ". The layer shows the other " + (total - missing) + ".";
        return what + " The session was probably saved on another computer. Put the files in the same folder as the"
                + " .jhv file and open the session again, or ask whoever saved it to use File > Export Session with Data,"
                + " which writes a .data.zip file beside the .jhv that carries them."; // SessionArchive.SUFFIX
    }

    private MissingFiles() {}
}
