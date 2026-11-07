package org.helioviewer.jhv.io;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.json.JSONObject;

/**
 * The FITS library as Studio's one store (LibraryStore): a download found again through
 * library-moves.tsv by its cache name or through the index's file_sources by its URL, a hit whose
 * file is gone counts as a miss, and NetFileCache.cachedFile prefers Studio's cache, then the library.
 * With HFCORE_CORE set to a real core (pyHelioFITS schema 6 or newer), a FITS file is also moved out
 * of the cache into a scratch library, a second copy of it is removed as a duplicate, and both are
 * found again by URL.
 *
 * <p>Every home, config folder and library here is a temp directory.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.io.LibraryStoreCheck
 */
public final class LibraryStoreCheck {

    private static final Path FIXTURE = Path.of("extra/test/data/PUNCH_L3_CAM_20260425001600_v0k.fits");
    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("hfs-onestore").toRealPath(); // real: on macOS /var is /private/var, and HelioFITS Core answers with real paths
        System.setProperty("user.home", home.toString());
        Path configDir = FitsLibrary.configDir();
        if (!configDir.startsWith(home)) {
            System.out.println("SKIP: HFCORE_HOME, APPDATA or XDG_CONFIG_HOME is set, so the library lookup does not follow user.home");
            return;
        }
        Directories.FILECACHE.getFile().mkdirs();
        Directories.SETTINGS.getFile().mkdirs(); // autoCreate marks itself done in Settings
        Path root = home.resolve("HelioFITSLibrary");
        Files.createDirectories(root.resolve("store"));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), new JSONObject().put("library_root", root.toString()).toString());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("hfcore.sqlite"));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)");
            st.execute("INSERT INTO meta VALUES ('reader_compat', '1')");
            st.execute("CREATE TABLE files(id INTEGER PRIMARY KEY, relpath TEXT)");
            st.execute("CREATE TABLE file_sources(url TEXT PRIMARY KEY, file_id INTEGER, recorded_at TEXT)");
            st.execute("INSERT INTO files VALUES (1, 'store/by-url.fits'), (2, 'store/gone.fits')");
            st.execute("INSERT INTO file_sources VALUES ('https://a.example/by-url.fits', 1, 't'), ('https://a.example/gone.fits', 2, 't')");
        }
        Files.writeString(root.resolve("store/by-url.fits"), "x");
        Files.writeString(root.resolve("store/moved.fits"), "y");

        URI byUrl = URI.create("https://a.example/by-url.fits");
        URI moved = URI.create("https://a.example/moved.fits?token=secret");
        URI gone = URI.create("https://a.example/gone.fits");
        URI never = URI.create("https://a.example/never.fits");

        File hit = LibraryStore.lookup(byUrl, NetFileCache.cacheNames(byUrl));
        expect("a URL the index knows finds the stored file", hit != null && hit.toPath().equals(root.resolve("store/by-url.fits")));
        expect("a stored file that is gone is a miss", LibraryStore.lookup(gone, NetFileCache.cacheNames(gone)) == null);
        expect("a URL nobody stored is a miss", LibraryStore.lookup(never, NetFileCache.cacheNames(never)) == null);

        LibraryStore.remember(root, NetFileCache.cacheNames(moved).getFirst(), "store/moved.fits");
        LibraryStore.reset(); // read the moves file back, as a new launch would
        File m = LibraryStore.lookup(moved, NetFileCache.cacheNames(moved));
        expect("a file moved from the cache is found by its cache name, after a relaunch",
                m != null && m.toPath().equals(root.resolve("store/moved.fits")));
        LibraryStore.remember(root, NetFileCache.cacheNames(never).get(1), "store/moved.fits");
        File legacy = LibraryStore.lookup(never, NetFileCache.cacheNames(never));
        expect("a file moved under its pre-2026-09-28 digest name is found too", legacy != null);
        URI elsewhere = URI.create("https://a.example/elsewhere.fits");
        LibraryStore.remember(home.resolve("OtherLibrary"), NetFileCache.cacheNames(elsewhere).getFirst(), "store/moved.fits");
        LibraryStore.reset();
        expect("a move recorded for another library (one used before a change of folder) is not followed",
                LibraryStore.lookup(elsewhere, NetFileCache.cacheNames(elsewhere)) == null);

        File fits = home.resolve("probe.fits").toFile();
        Files.copy(FIXTURE, fits.toPath());
        expect("no library is made by itself off macOS", LibraryStore.autoCreate(fits, false) == null);
        String config = Files.readString(configDir.resolve("config.json"));
        expect("nor where HelioFITS Core already has a config (a library on a drive not connected is never replaced)",
                LibraryStore.autoCreate(fits, true) == null && Files.readString(configDir.resolve("config.json")).equals(config));

        File inCache = NetFileCache.cachedFile(byUrl);
        expect("cachedFile gives the library's copy when the cache has none", inCache.toPath().equals(root.resolve("store/by-url.fits")));
        File staged = new File(Directories.FILECACHE.getFile(), NetFileCache.cacheNames(byUrl).getFirst());
        Files.writeString(staged.toPath(), "z");
        expect("cachedFile prefers a file still in Studio's cache", NetFileCache.cachedFile(byUrl).equals(staged));
        File none = NetFileCache.cachedFile(gone);
        expect("cachedFile gives the cache path, not there yet, on a miss",
                !none.exists() && none.getParentFile().equals(Directories.FILECACHE.getFile()));

        Files.delete(root.resolve("hfcore.sqlite"));
        expect("with the library unreachable (an unplugged drive), nothing is found and nothing throws",
                LibraryStore.lookup(byUrl, NetFileCache.cacheNames(byUrl)) == null);

        realCore(home);
        autoCreateReal(fits);
        System.out.println(failures == 0 ? "LibraryStoreCheck: all passed" : "LibraryStoreCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** With a real core: move a download into a scratch library made by hfcore init, then a duplicate. */
    private static void realCore(Path home) throws Exception {
        String core = System.getenv("HFCORE_CORE");
        if (core == null || core.isEmpty()) {
            System.out.println("  skip a real HelioFITS Core: HFCORE_CORE is not set");
            return;
        }
        Path root = home.resolve("RealLibrary");
        java.util.List<String> synced = FitsCoreIngest.useLibrary(home.resolve("Dropbox").resolve("Library"));
        expect("a folder in a sync service comes back with HelioFITS Core's warning, got " + synced,
                synced.size() == 1 && synced.getFirst().contains("sync service"));
        java.util.List<String> warnings = FitsCoreIngest.useLibrary(root); // Settings > FITS Library > Choose Folder...
        FitsLibrary.Location lib = FitsLibrary.find();
        expect("choosing a new folder made the library Studio finds, got " + lib + ", warnings " + warnings,
                lib != null && lib.root().equals(root) && warnings.isEmpty());
        URI uri = URI.create("https://archive.example/" + FIXTURE.getFileName());
        File first = new File(Directories.FILECACHE.getFile(), NetFileCache.cacheNames(uri).getFirst());
        Files.copy(FIXTURE, first.toPath());
        File stored = LibraryStore.adopt(uri, first);
        if (stored == null && !FitsCoreIngest.consumes()) {
            expect("a core without consume (older than pyHelioFITS schema 6) leaves the download in Studio's cache", first.isFile());
            System.out.println("  skip the move itself: this HelioFITS Core cannot take files over");
            return;
        }
        expect("the download moved into the library, got " + stored,
                stored != null && stored.toPath().startsWith(root) && !first.exists());
        LibraryStore.reset();
        File found = LibraryStore.lookup(uri, NetFileCache.cacheNames(uri));
        expect("and is found again by its URL", found != null && found.equals(stored));
        URI mirror = URI.create("https://mirror.example/" + FIXTURE.getFileName());
        File second = new File(Directories.FILECACHE.getFile(), NetFileCache.cacheNames(mirror).getFirst());
        Files.copy(FIXTURE, second.toPath());
        File dup = LibraryStore.adopt(mirror, second);
        expect("a second download of the same bytes leaves nothing in the cache and points at the stored file",
                dup != null && dup.equals(stored) && !second.exists());
        expect("the mirror URL is found through the index too",
                lib != null && FitsLibrary.bySource(lib, FitsCoreIngest.provenance(mirror)) != null);
    }

    /** With a real core, on a fresh home with no config: the first FITS download makes ~/HelioFITSLibrary, once. */
    private static void autoCreateReal(File fits) throws Exception {
        if (System.getenv("HFCORE_CORE") == null || System.getenv("HFCORE_CORE").isEmpty()) {
            System.out.println("  skip making a library by itself: HFCORE_CORE is not set");
            return;
        }
        Path home = Files.createTempDirectory("hfs-autolib").toRealPath();
        System.setProperty("user.home", home.toString());
        LibraryStore.reset();
        File text = home.resolve("notes.txt").toFile();
        Files.writeString(text.toPath(), "not FITS");
        expect("a file that is not FITS makes no library", LibraryStore.autoCreate(text, true) == null);
        FitsLibrary.Location lib = LibraryStore.autoCreate(fits, true);
        expect("the first FITS download on a Mac with no library makes ~/HelioFITSLibrary, got " + lib,
                lib != null && lib.root().equals(home.resolve("HelioFITSLibrary")));
        Files.delete(FitsLibrary.configDir().resolve("config.json"));
        expect("and only once", LibraryStore.autoCreate(fits, true) == null);
    }

    private LibraryStoreCheck() {}
}
