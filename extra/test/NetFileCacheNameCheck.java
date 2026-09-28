package org.helioviewer.jhv.io;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Cached files are named after the file they came from, with a short hash of the URL before the
 * extension, and a file cached under the old bare-digest name is renamed the first time its URL is
 * asked for. No network is touched.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.io.NetFileCacheNameCheck
 */
public final class NetFileCacheNameCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    public static void main(String[] args) throws Exception {
        // Before Directories is touched: HOME reads user.home once, when the enum initialises.
        System.setProperty("user.home", Files.createTempDirectory("hfs-netfilecache-name").toString());
        org.helioviewer.jhv.app.Platform.init();
        Directories.createPersistentDirs();

        String h = "0123456789abcdef";
        expect("plain name keeps its extension after the hash",
                NetFileCache.readableName(URI.create("https://umbra.nascom.nasa.gov/punch/3/CAM/PUNCH_L3_CAM_20260326062334_v0l.fits"), h)
                        .equals("PUNCH_L3_CAM_20260326062334_v0l_0123456789ab.fits"));
        expect("a compound extension stays whole",
                NetFileCache.readableName(URI.create("https://x.invalid/a/b/frame.fits.gz"), h).equals("frame_0123456789ab.fits.gz"));
        expect("characters a file system would trip on become underscores",
                NetFileCache.readableName(URI.create("https://x.invalid/a%20b(1).fts"), h).equals("a_b_1__0123456789ab.fts"));
        expect("a URL with no file name still gets a name",
                NetFileCache.readableName(URI.create("https://x.invalid/"), h).equals("0123456789ab"));

        URI uri = URI.create("https://example.invalid/lasco/c3/22713085.fts");
        File dir = Directories.FILECACHE.getFile();
        File legacy = new File(dir, sha256(uri.toString()));
        Files.writeString(legacy.toPath(), "frame bytes");
        File found = NetFileCache.cachedFile(uri);
        expect("an old digest-named file is renamed to the readable name on first lookup",
                found.getName().startsWith("22713085_") && found.getName().endsWith(".fts") && found.isFile() && !legacy.exists());
        expect("and keeps its bytes", Files.readString(found.toPath()).equals("frame bytes"));
        expect("a second lookup finds the same file", NetFileCache.cachedFile(uri).equals(found));

        System.out.println(failures == 0 ? "NetFileCacheNameCheck: PASS" : "NetFileCacheNameCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }
}
