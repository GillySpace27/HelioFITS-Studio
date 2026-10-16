package org.helioviewer.jhv.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ~/HFStudio/Datasets/&lt;dataset&gt;/&lt;file&gt;: the cache as folders a Finder or a shell can browse, one
 * symbolic link per cached frame, grouped the way the cache dialog groups them (CacheIndex.group), so a
 * long gap never splits a dataset in two here either. The links are the only thing written: the cache
 * is never touched, and a link that no longer points where the cache says is moved into
 * {@code .stale/} (renamed if the name is taken), never deleted.
 */
public final class DatasetLinks {

    static final String STALE = ".stale";

    /** Rebuilds the folder under the real home; returns it. */
    public static Path rebuild() throws IOException {
        Path root = Path.of(Directories.HOME.getPath(), "Datasets");
        rebuild(root, CacheIndex.group(CacheIndex.scan(null)));
        return root;
    }

    /** Pure of the cache and the home, so DatasetLinksCheck can drive it with a scratch folder. */
    static void rebuild(Path root, List<CacheIndex.Dataset> datasets) throws IOException {
        Files.createDirectories(root);
        for (CacheIndex.Dataset set : datasets) {
            Path dir = root.resolve(safe(set.key()));
            Files.createDirectories(dir);
            for (File file : CacheIndex.files(set)) {
                Path link = dir.resolve(file.getName());
                Path target = file.toPath().toAbsolutePath();
                if (Files.isSymbolicLink(link)) {
                    if (Files.readSymbolicLink(link).equals(target))
                        continue;
                    retire(link);
                } else if (Files.exists(link)) {
                    continue; // something that is not ours: leave it
                }
                if (Files.exists(target))
                    Files.createSymbolicLink(link, target);
            }
        }
    }

    /** A folder name from a dataset key: letters, digits and . _ - only, never empty or a dot name. */
    static String safe(String key) {
        String s = key.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() || s.chars().allMatch(c -> c == '.') ? "dataset" : s;
    }

    private static void retire(Path link) throws IOException {
        Path stale = link.getParent().getParent().resolve(STALE);
        Files.createDirectories(stale);
        Path to = stale.resolve(link.getParent().getFileName() + "__" + link.getFileName());
        for (int i = 1; Files.exists(to, java.nio.file.LinkOption.NOFOLLOW_LINKS); i++)
            to = stale.resolve(link.getParent().getFileName() + "__" + link.getFileName() + "." + i);
        Files.move(link, to);
    }

    private DatasetLinks() {}
}
