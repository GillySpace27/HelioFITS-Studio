package org.helioviewer.jhv.io;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;

/**
 * The cache cap: oldest-first eviction to the cap, files a session names are kept, temp downloads
 * are left alone.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.io.CacheCapCheck
 */
public final class CacheCapCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("capcheck").toFile();
        for (int i = 0; i < 5; i++) {
            File f = new File(dir, "f" + i);
            Files.write(f.toPath(), new byte[100]);
            f.setLastModified(1_000_000L + i * 1000L); // f0 oldest
        }
        File tmp = new File(dir, "dl123.tmp");
        Files.write(tmp.toPath(), new byte[100]);
        tmp.setLastModified(1L);

        int n = CacheCap.evict(dir, 250, Set.of("f0"));
        expect("f0 kept (a session names it)", new File(dir, "f0").exists());
        expect("oldest unprotected f1, f2, f3 removed to reach the cap (500 -> 200 bytes)", !new File(dir, "f1").exists() && !new File(dir, "f2").exists() && !new File(dir, "f3").exists());
        expect("f4 kept once under the cap", new File(dir, "f4").exists());
        expect("temp download untouched", tmp.exists());
        expect("three removed", n == 3);

        String session = "{\"imageLayers\":[{\"uri\":\"https:\\/\\/example.org\\/a\\/PUNCH_L3_CAM_x.fits\"}]}";
        expect("session URL maps to its cache name",
                CacheCap.namesIn(session).containsAll(NetFileCache.cacheNames(java.net.URI.create("https://example.org/a/PUNCH_L3_CAM_x.fits"))));

        System.out.println(failures == 0 ? "CacheCapCheck: ok" : "CacheCapCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
