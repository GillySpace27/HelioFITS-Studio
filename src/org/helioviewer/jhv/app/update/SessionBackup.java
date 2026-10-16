package org.helioviewer.jhv.app.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Copies of the session and settings, taken before an update is downloaded.
 *
 * <p>Copy, never move, and never delete: the originals stay exactly where the running copy and the
 * next version expect them, and a copy that went wrong leaves the user with two sets instead of none.
 * The folder is new each time ({@code createDirectory} refuses one that exists) and nothing is copied
 * over an existing file, so an earlier backup can never be overwritten by a later one.
 *
 * <p>Pure file work, so UpdateBackupCheck runs it against a throwaway home.
 */
public final class SessionBackup {

    /** What a backup copied: the folder, the number of files and their bytes. */
    public record Result(Path folder, int files, long bytes) {}

    /** A thing to keep: a file, or a folder copied with the files {@code include} accepts. */
    public record Source(Path path, Predicate<Path> include) {}

    /**
     * Copy each source under {@code root/folderName/<label>}. Throws on the first failure; the caller
     * then stops the update. Whatever was copied before the failure stays (nothing is cleaned up,
     * because cleaning up is deleting). A source that does not exist is skipped: there is nothing of
     * it to lose.
     */
    public static Result copy(Path root, String folderName, Map<String, Source> sources) throws IOException {
        if (folderName.contains("/") || folderName.contains("\\") || folderName.isBlank() || folderName.startsWith("."))
            throw new IOException("Not a folder name: " + folderName);
        Files.createDirectories(root);
        Path folder = Files.createDirectory(root.resolve(folderName));
        int files = 0;
        long bytes = 0;
        for (Map.Entry<String, Source> e : sources.entrySet()) {
            Path from = e.getValue().path();
            Path to = folder.resolve(e.getKey());
            if (Files.isRegularFile(from)) {
                bytes += copyOne(from, to);
                files++;
            } else if (Files.isDirectory(from)) {
                List<Path> walk;
                try (Stream<Path> s = Files.walk(from)) {
                    walk = s.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                            .filter(e.getValue().include()).toList();
                }
                for (Path p : walk) {
                    bytes += copyOne(p, to.resolve(from.relativize(p).toString()));
                    files++;
                }
            }
        }
        return new Result(folder, files, bytes);
    }

    // Read whole, then written with CREATE_NEW: a clash is a failure, never an overwrite. Reading the
    // file in one go copies one version of it even while autosave moves a new one into place (sessions
    // and settings are small JSON and properties files); the length on disk is compared afterwards, so
    // a short write is caught here rather than found after the update.
    private static long copyOne(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        FileTime modified = Files.getLastModifiedTime(from);
        byte[] data = Files.readAllBytes(from);
        Files.write(to, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        long have = Files.size(to);
        if (have != data.length)
            throw new IOException("The copy of " + from + " is " + have + " bytes, the file is " + data.length);
        Files.setLastModifiedTime(to, modified);
        return have;
    }

    private SessionBackup() {}
}
