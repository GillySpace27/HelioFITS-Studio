package org.helioviewer.jhv.io;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Moves what is in one storage folder into another, for "Move existing files" in Settings > Storage.
 *
 * <p>Every top-level entry goes as a unit and each file keeps its name and its place beside its
 * neighbours, so an export stays next to its {@code .hfrec} sidecar (and {@code .hfrec.json}), a
 * {@code python-*} folder keeps its {@code hfrecord.json}, and a cache file keeps the hashed name
 * Studio looks it up by. Nothing is overwritten: a name already at the destination stays where it
 * is and is counted as skipped. A file is deleted from the source only after its copy has the same
 * length, so an interrupted move leaves every file in at least one place.
 */
public final class StorageMove {

    /** What happened: files moved, files left because the destination had one, files that failed. */
    public record Result(int moved, int skipped, int failed, long bytes, List<String> problems) {}

    /** Names that are never moved: a download in flight is not a file yet. */
    private static boolean ignored(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".tmp") || n.equals(".DS_Store");
    }

    /** Whether {@code inner} is the same folder as {@code outer} or inside it (real paths, so links count). */
    static boolean inside(Path outer, Path inner) {
        try {
            return inner.toRealPath().startsWith(outer.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Move the contents of {@code from} into {@code to}.
     *
     * @param progress told the number of files handled so far
     * @throws IOException when the two folders overlap or the destination cannot be created
     */
    public static Result move(Path from, Path to, Consumer<Integer> progress) throws IOException {
        if (!Files.isDirectory(from))
            return new Result(0, 0, 0, 0, List.of());
        Files.createDirectories(to);
        if (inside(from, to) || inside(to, from))
            throw new IOException("The two folders are the same, or one is inside the other.");

        int[] count = new int[4]; // moved, skipped, failed, handled
        long[] bytes = new long[1];
        List<String> problems = new ArrayList<>();
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (ignored(file))
                    return FileVisitResult.CONTINUE;
                Path dest = to.resolve(from.relativize(file).toString());
                progress.accept(++count[3]);
                if (Files.exists(dest)) {
                    count[1]++;
                    return FileVisitResult.CONTINUE;
                }
                try {
                    Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES);
                    if (Files.size(dest) != Files.size(file)) {
                        Files.deleteIfExists(dest);
                        throw new IOException("copy is a different size");
                    }
                    Files.delete(file);
                    count[0]++;
                    bytes[0] += attrs.size();
                } catch (IOException e) {
                    count[2]++;
                    problems.add(file + ": " + e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (!dir.equals(from)) {
                    try {
                        Files.deleteIfExists(dir); // only when nothing is left in it
                    } catch (IOException ignoredNotEmpty) {
                        // skipped or failed files keep their folder
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new Result(count[0], count[1], count[2], bytes[0], List.copyOf(problems));
    }

    /** Total size in bytes of the files under {@code dir}; 0 when it is not there. Follows no links. */
    public static long size(Path dir) {
        if (!Files.isDirectory(dir))
            return 0;
        long[] total = new long[1];
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // a size that is too small is better than none
        }
        return total[0];
    }

    private StorageMove() {}
}
