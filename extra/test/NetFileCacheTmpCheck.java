package org.helioviewer.jhv.io;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * The download temp files in the file cache: a failed download removes its own, and the ones a dead
 * process left behind are swept on first use, but only once they are old enough that no second
 * running instance can still be writing them.
 *
 * <p>The failing download fails with an Error, not an IOException, and that is on purpose: the
 * one exception type the code used to clean up after was the one kind of failure that did not leak.
 * {@code NetClientRemote} cannot initialise here because {@code Directories.createCacheDirs()} is
 * never called, so its HTTP cache directory is null. No network is touched.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.io.NetFileCacheTmpCheck
 */
public final class NetFileCacheTmpCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static File touch(File dir, String name, long mtime) throws Exception {
        File f = new File(dir, name);
        Files.writeString(f.toPath(), "x");
        f.setLastModified(mtime);
        return f;
    }

    public static void main(String[] args) throws Exception {
        // Before Directories is touched: HOME reads user.home once, when the enum initialises.
        System.setProperty("user.home", Files.createTempDirectory("hfs-netfilecache").toString());
        org.helioviewer.jhv.app.Platform.init();
        Directories.createPersistentDirs();

        File dir = Directories.FILECACHE.getFile();
        long now = System.currentTimeMillis();
        long twoHoursAgo = now - 2 * 3600_000L;
        File stale = touch(dir, "dl123.tmp", twoHoursAgo);
        File fresh = touch(dir, "dl456.tmp", now);
        File frame = touch(dir, "0123abcd", twoHoursAgo);
        File notOurs = touch(dir, "index.tmp", twoHoursAgo);

        Throwable thrown = null;
        try {
            NetFileCache.get(URI.create("https://example.invalid/frame.fits"));
        } catch (Throwable t) {
            thrown = t;
        }
        expect("the download failed, as arranged (" + thrown + ")", thrown != null);

        expect("an hour-old download temp file is swept", !stale.exists());
        expect("a fresh one is kept, since another instance may be writing it", fresh.exists());
        expect("a published frame is kept however old", frame.exists());
        expect("a .tmp that is not a download's is kept", notOurs.exists());

        String[] temps = dir.list((d, name) -> name.startsWith("dl") && name.endsWith(".tmp"));
        expect("the failed download left no temp file of its own " + Arrays.toString(temps),
                temps != null && temps.length == 1 && temps[0].equals(fresh.getName()));

        System.out.println(failures == 0 ? "NetFileCacheTmpCheck: all ok" : "NetFileCacheTmpCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

}
