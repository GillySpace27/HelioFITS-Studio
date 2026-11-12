package org.helioviewer.jhv.io;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.json.JSONObject;

/**
 * Other installs seen read-only (Siblings, LibraryStore): a sibling home is found and this one is not,
 * its recent sessions and States are listed, a download only the sibling moved is found through its
 * library-moves.tsv (and only for the same library root), the import copies a state once and never
 * overwrites, and switching it off finds nothing. Every home and library here is a temp directory.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.io.SiblingsCheck
 */
public final class SiblingsCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("hfs-siblings").toRealPath();
        System.setProperty("user.home", home.toString());
        Path configDir = FitsLibrary.configDir();
        if (!configDir.startsWith(home) || System.getenv("HFS_HOME") != null) {
            System.out.println("SKIP: HFS_HOME, HFCORE_HOME, APPDATA or XDG_CONFIG_HOME is set, so homes do not follow user.home");
            return;
        }
        Directories.SETTINGS.getFile().mkdirs();
        Directories.FILECACHE.getFile().mkdirs();

        Path root = home.resolve("Lib");
        Files.createDirectories(root.resolve("store"));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), new JSONObject().put("library_root", root.toString()).toString());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("hfcore.sqlite"));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)");
            st.execute("INSERT INTO meta VALUES ('reader_compat', '1')");
            st.execute("CREATE TABLE files(id INTEGER PRIMARY KEY, relpath TEXT)");
            st.execute("CREATE TABLE file_sources(url TEXT PRIMARY KEY, file_id INTEGER, recorded_at TEXT)");
        }
        Files.writeString(root.resolve("store/theirs.fits"), "x");

        Path other = home.resolve("HFStudio-debug");
        Files.createDirectories(other.resolve("Settings"));
        Files.createDirectories(other.resolve("States"));
        Files.createDirectories(other.resolve("FileCache"));
        Path session = other.resolve("States/mine.jhv");
        Files.writeString(session, "{}");
        Files.writeString(other.resolve("Settings/user.properties"),
                "recentSessions=" + session + "\\n" + home.resolve("gone.jhv") + "\n");
        Files.writeString(other.resolve("FileCache/library-moves.tsv"),
                "abc\tstore/theirs.fits\t" + root + "\nbad line\ndef\tstore/theirs.fits\t/some/other/library\nhalf");
        Files.createDirectories(home.resolve("HFStudio/Settings")); // this install: never its own sibling

        expect("the other install is found, this one is not", Siblings.homes().equals(List.of(other)));
        expect("its live recent session is listed, the missing one is not",
                Siblings.recents().size() == 1 && Siblings.recents().get(0).file().equals(session.toFile()));
        expect("its States are listed", Siblings.states().size() == 1);

        File found = LibraryStore.lookup(URI.create("https://x.example/n.fits"), List.of("abc"));
        expect("a download only the other install moved is found", found != null && found.equals(root.resolve("store/theirs.fits").toFile()));
        expect("a line about another library is not followed", LibraryStore.lookup(URI.create("https://x.example/n.fits"), List.of("def")) == null);
        expect("an unknown name misses", LibraryStore.lookup(URI.create("https://x.example/n.fits"), List.of("zzz")) == null);

        File copy = Siblings.importState(session.toFile());
        expect("import copies a state into States", copy != null && copy.isFile() && copy.getParentFile().equals(Directories.STATES.getFile()));
        expect("import twice is a no-op", Siblings.importState(session.toFile()) == null);
        expect("the source is untouched", Files.readString(session).equals("{}"));
        expect("import of the moves copies the one good line", LibraryStore.importMoves() == 1 && LibraryStore.importMoves() == 0);
        expect("the other install's list was not written to",
                Files.readString(other.resolve("FileCache/library-moves.tsv")).endsWith("half"));

        org.helioviewer.jhv.app.Settings.setProperty(Siblings.ENABLED_KEY, "false");
        expect("switched off, nothing is found", Siblings.homes().isEmpty());

        System.out.println(failures == 0 ? "PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
