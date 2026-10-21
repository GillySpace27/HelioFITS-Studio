package org.helioviewer.jhv.io;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * DatasetLinks: one folder per dataset, one link per frame, a moved target retires the old link into
 * .stale instead of deleting it, a regular file already in place is left alone, and a key can never
 * name a path outside the folder. In listing mode (Windows) each folder holds files.txt instead.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.io.DatasetLinksCheck
 */
public final class DatasetLinksCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static CacheIndex.Dataset set(String key, String... names) {
        List<CacheIndex.Frame> frames = new java.util.ArrayList<>();
        long t = 0;
        for (String n : names)
            frames.add(new CacheIndex.Frame(n, key, key, "L1", "x", "0a", t++, 1));
        return CacheIndex.group(frames).getFirst();
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-datasets").toString());
        Directories.createCacheDirs();
        Path cache = Directories.FILECACHE.getFile().toPath();
        Files.createDirectories(cache);
        for (String n : new String[]{"a1.fits", "a2.fits", "b1.fits"})
            Files.writeString(cache.resolve(n), n);
        Path root = Files.createTempDirectory("hfs-datasets-root").resolve("Datasets");

        List<CacheIndex.Dataset> sets = List.of(set("AIA_171", "a1.fits", "a2.fits"), set("../../etc", "b1.fits"));
        DatasetLinks.rebuild(root, sets, true);
        expect("a link per frame under its dataset", Files.isSymbolicLink(root.resolve("AIA_171/a1.fits"))
                && Files.isSymbolicLink(root.resolve("AIA_171/a2.fits")));
        expect("a link reads the cached file", Files.readString(root.resolve("AIA_171/a2.fits")).equals("a2.fits"));
        expect("a key cannot leave the folder", Files.isSymbolicLink(root.resolve(DatasetLinks.safe("../../etc") + "/b1.fits"))
                && !DatasetLinks.safe("../../etc").contains("/"));

        Files.delete(root.resolve("AIA_171/a1.fits"));
        Files.createSymbolicLink(root.resolve("AIA_171/a1.fits"), cache.resolve("elsewhere.fits"));
        DatasetLinks.rebuild(root, sets, true);
        expect("a wrong link is repointed", Files.readString(root.resolve("AIA_171/a1.fits")).equals("a1.fits"));
        expect("and the old one is kept in .stale, not deleted",
                Files.isSymbolicLink(root.resolve(".stale/AIA_171__a1.fits")));

        Files.delete(root.resolve("AIA_171/a2.fits"));
        Files.writeString(root.resolve("AIA_171/a2.fits"), "mine");
        DatasetLinks.rebuild(root, sets, true);
        expect("a regular file that is not ours is left alone", Files.readString(root.resolve("AIA_171/a2.fits")).equals("mine"));
        expect("the cache itself is untouched", Files.readString(cache.resolve("a2.fits")).equals("a2.fits"));

        // Windows: a files.txt listing per dataset instead of links; a foreign files.txt is left alone.
        Path listed = Files.createTempDirectory("hfs-datasets-listed").resolve("Datasets");
        DatasetLinks.rebuild(listed, sets, false);
        Path listing = listed.resolve("AIA_171/" + DatasetLinks.LISTING);
        List<String> lines = Files.isRegularFile(listing) ? Files.readAllLines(listing) : List.of();
        expect("listing mode writes files.txt under its header", !lines.isEmpty() && lines.getFirst().equals(DatasetLinks.HEADER));
        expect("listing names every cached frame by absolute path",
                lines.contains(cache.resolve("a1.fits").toAbsolutePath().toString())
                        && lines.contains(cache.resolve("a2.fits").toAbsolutePath().toString()));
        expect("listing mode makes no links", !Files.exists(listed.resolve("AIA_171/a1.fits"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        Files.writeString(listing, "my notes");
        DatasetLinks.rebuild(listed, sets, false);
        expect("a files.txt that is not ours is left alone", Files.readString(listing).equals("my notes"));

        if (failures > 0) {
            System.out.println("DatasetLinksCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("DatasetLinksCheck: all passed");
    }
}
