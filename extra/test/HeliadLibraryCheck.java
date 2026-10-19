package org.helioviewer.jhv.io;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.json.JSONObject;

/**
 * Reading a Heliad library the way its reader contract says to: where the config is on each
 * platform, what counts as no library, what the Heliad tab lists for a master range, which files
 * an Add loads, that an index from a newer Heliad is refused, and that reading leaves no trace.
 *
 * <p>The index is a small SQLite file built here with the parts of Heliad's schema that
 * {@code current_files} reads, including a superseded file version, so a reader that looked at
 * the files table instead of the view would count it. Nothing outside a temp folder is touched:
 * {@code user.home} is moved there first, because the log and the app folders hang off it.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.io.HeliadLibraryCheck
 */
public final class HeliadLibraryCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static long t(String iso) {
        return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static final String SCHEMA = """
            CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE datasets (id INTEGER PRIMARY KEY, dataset_key TEXT NOT NULL UNIQUE, observatory TEXT,
                instrument TEXT, detector TEXT, product TEXT, level TEXT, wavelength REAL, label TEXT NOT NULL);
            CREATE TABLE observations (id INTEGER PRIMARY KEY, obs_key TEXT NOT NULL UNIQUE,
                dataset_id INTEGER NOT NULL REFERENCES datasets(id), date_obs TEXT NOT NULL, current_file_id INTEGER);
            CREATE TABLE files (id INTEGER PRIMARY KEY, sha256 TEXT NOT NULL UNIQUE, relpath TEXT NOT NULL UNIQUE,
                size INTEGER NOT NULL, observation_id INTEGER NOT NULL, version TEXT);
            CREATE VIEW current_files AS
            SELECT f.id AS file_id, f.relpath, f.sha256, f.size, f.version,
                   o.id AS observation_id, o.date_obs,
                   d.id AS dataset_id, d.dataset_key, d.label, d.observatory, d.instrument,
                   d.detector, d.product, d.level, d.wavelength
            FROM observations o
            JOIN files f    ON f.id = o.current_file_id
            JOIN datasets d ON d.id = o.dataset_id;
            INSERT INTO meta VALUES ('schema_version', '3'), ('reader_compat', '1');
            INSERT INTO datasets VALUES (1, 'sdo|aia|193', 'SDO', 'AIA', NULL, NULL, '1', 193.0, 'SDO AIA 193 L1'),
                                        (2, 'punch|wfi', 'PUNCH', 'WFI', NULL, NULL, '3', NULL, 'PUNCH WFI L3'),
                                        (3, 'odd', NULL, NULL, NULL, NULL, NULL, NULL, 'Odd');
            INSERT INTO observations VALUES (1, 'a1', 1, '2025-09-21T16:00:04.840', 1),
                                            (2, 'a2', 1, '2025-09-21T16:01:00.000', 2),
                                            (3, 'a3', 1, '2025-09-21T16:02:04.840', 4),
                                            (4, 'a4', 1, '2025-09-21T16:03:00.000', 5),
                                            (5, 'a5', 1, '2025-09-21T16:04:04.840', 6),
                                            (6, 'p1', 2, '2025-10-01T00:00:00.000', 7),
                                            (7, 'p2', 2, '2025-10-01T00:04:00.000', 8),
                                            (8, 'o1', 3, 'not a time', 9);
            INSERT INTO files VALUES (1, 's1', 'store/aia/a1.fits', 1000, 1, NULL),
                                     (2, 's2', 'store/aia/a2.fits', 1000, 2, NULL),
                                     (3, 's3', 'store/aia/a3_v1.fits', 100, 3, '1'),
                                     (4, 's4', 'store/aia/a3_v2.fits', 1000, 3, '2'),
                                     (5, 's5', 'store/aia/a4.fits', 1000, 4, NULL),
                                     (6, 's6', 'store/aia/a5.fits', 1000, 5, NULL),
                                     (7, 's7', 'store/punch/p1.fits', 5000, 6, NULL),
                                     (8, 's8', 'store/punch/p2.fits', 5000, 7, NULL),
                                     (9, 's9', 'store/odd/o1.fits', 10, 8, NULL);
            """;

    private static void sql(Path db, String statements) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = conn.createStatement()) {
            for (String one : statements.split(";"))
                if (!one.isBlank())
                    st.executeUpdate(one);
        }
    }

    private static String config(Path root, Path db) {
        return new JSONObject().put("library_root", root.toString()).put("db_path", db.toString()).toString();
    }

    private static List<Path> listing(Path dir) throws Exception {
        try (Stream<Path> all = Files.walk(dir)) {
            return all.sorted().toList();
        }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("heliad-library-check");
        Path fakeHome = Files.createDirectories(tmp.resolve("home"));
        System.setProperty("user.home", fakeHome.toString());

        // -- where Heliad keeps config.json ----------------------------------------------------
        Path home = Path.of("/Users/someone");
        expect("macOS: ~/Library/Application Support/Heliad",
                HeliadLibrary.configDir(null, "Mac OS X", home, null, null).equals(home.resolve("Library/Application Support/Heliad")));
        expect("Linux: ~/.config/heliad when XDG_CONFIG_HOME is unset",
                HeliadLibrary.configDir(null, "Linux", home, null, null).equals(home.resolve(".config/heliad")));
        expect("Linux: XDG_CONFIG_HOME/heliad when it is set",
                HeliadLibrary.configDir(null, "Linux", home, null, "/xdg").equals(Path.of("/xdg/heliad")));
        expect("Windows: %APPDATA%/Heliad",
                HeliadLibrary.configDir(null, "Windows 11", home, "/roaming", null).equals(Path.of("/roaming/Heliad")));
        expect("Windows: ~/AppData/Roaming/Heliad without APPDATA",
                HeliadLibrary.configDir(null, "Windows 11", home, null, null).equals(home.resolve("AppData/Roaming/Heliad")));
        expect("HELIAD_HOME replaces the folder on every platform",
                HeliadLibrary.configDir("/elsewhere", "Mac OS X", home, null, null).equals(Path.of("/elsewhere"))
                        && HeliadLibrary.configDir("/elsewhere", "Linux", home, null, "/xdg").equals(Path.of("/elsewhere")));
        expect("an empty HELIAD_HOME counts as unset",
                HeliadLibrary.configDir("", "Linux", home, null, null).equals(home.resolve(".config/heliad")));

        // -- what counts as no library -------------------------------------------------------
        Path cfg = tmp.resolve("cfg");
        expect("no config folder is no library", HeliadLibrary.read(cfg) == null);
        Files.createDirectories(cfg);
        expect("an empty config folder is no library", HeliadLibrary.read(cfg) == null);
        Path root = Files.createDirectories(tmp.resolve("library"));
        Path db = root.resolve("heliad.sqlite");
        Files.writeString(cfg.resolve("config.json"), config(root, db));
        expect("a config whose index is not there is no library, and nothing is created",
                HeliadLibrary.read(cfg) == null && !Files.exists(db));
        sql(db, SCHEMA);
        HeliadLibrary.Location loc = HeliadLibrary.read(cfg);
        expect("a config and its index are a library", loc != null && loc.root().equals(root) && loc.db().equals(db));
        Files.writeString(cfg.resolve("config.json"), new JSONObject().put("library_root", root.toString()).toString());
        HeliadLibrary.Location rootOnly = HeliadLibrary.read(cfg);
        expect("a config naming only the folder finds heliad.sqlite in it", rootOnly != null && rootOnly.db().equals(db));
        Path tilde = Files.createDirectories(fakeHome.resolve("lib2"));
        Files.copy(db, tilde.resolve("heliad.sqlite"));
        Files.writeString(cfg.resolve("config.json"), "{\"library_root\": \"~/lib2\", \"db_path\": \"~/lib2/heliad.sqlite\"}");
        HeliadLibrary.Location expanded = HeliadLibrary.read(cfg);
        expect("~ in the config is the home folder", expanded != null && expanded.root().equals(tilde));
        Files.writeString(cfg.resolve("config.json"), "{\"library_root\": ");
        expect("a config that is not JSON is no library rather than an error", HeliadLibrary.read(cfg) == null);
        Files.writeString(cfg.resolve("config.json"), config(root, db));

        // -- the datasets the tab lists ------------------------------------------------------
        List<Path> before = listing(root);
        byte[] bytesBefore = Files.readAllBytes(db);
        long from = t("2025-09-21T16:01:00"), to = t("2025-09-21T16:03:00");
        List<HeliadLibrary.Dataset> sets = HeliadLibrary.datasets(loc, from, to);
        expect("every dataset is listed, by label",
                sets.stream().map(HeliadLibrary.Dataset::label).toList().equals(List.of("Odd", "PUNCH WFI L3", "SDO AIA 193 L1")));
        HeliadLibrary.Dataset aia = sets.get(2);
        expect("frames count observations, not file versions", aia.frames() == 5);
        expect("bytes are the current versions' sizes", aia.bytes() == 5000);
        expect("observations on both ends of the range are in it", aia.inRange() == 3);
        expect("the span is the first and last observation",
                aia.start() == t("2025-09-21T16:00:04.840") && aia.end() == t("2025-09-21T16:04:04.840"));
        expect("instrument and wavelength come through for the Heliad link",
                "AIA".equals(aia.instrument()) && Double.valueOf(193.0).equals(aia.wavelength()));
        HeliadLibrary.Dataset punch = sets.get(1);
        expect("a dataset outside the range is listed with nothing in it", punch.inRange() == 0 && punch.frames() == 2);
        expect("a missing wavelength stays missing rather than reading as 0", punch.wavelength() == null);
        HeliadLibrary.Dataset odd = sets.getFirst();
        expect("a time the reader cannot parse keeps the dataset listed, its span unknown",
                odd.frames() == 1 && odd.start() == Long.MIN_VALUE && odd.end() == Long.MIN_VALUE && odd.inRange() == 0);

        // -- the files an Add loads ----------------------------------------------------------
        List<File> inRange = HeliadLibrary.files(loc, "sdo|aia|193", from, to);
        expect("an Add loads the in-range files in time order, resolved under the library",
                inRange.equals(Stream.of("store/aia/a2.fits", "store/aia/a3_v2.fits", "store/aia/a4.fits")
                        .map(p -> root.resolve(p).toFile()).toList()));
        expect("the whole dataset is every current file", HeliadLibrary.files(loc, "sdo|aia|193").size() == 5);
        expect("a range with nothing in it loads nothing", HeliadLibrary.files(loc, "punch|wfi", from, to).isEmpty());
        expect("reading created nothing beside the index", listing(root).equals(before));
        expect("and left the index as it was", Arrays.equals(Files.readAllBytes(db), bytesBefore));

        // -- reader_compat, which is the only version a reader may look at ---------------------
        sql(db, "UPDATE meta SET value = '99' WHERE key = 'schema_version'");
        expect("a newer schema_version alone does not stop the reader", HeliadLibrary.datasets(loc, from, to).size() == 3);
        sql(db, "UPDATE meta SET value = '2' WHERE key = 'reader_compat'");
        expect("an index from a newer Heliad is refused when listing", refused(() -> HeliadLibrary.datasets(loc, from, to)));
        expect("and when loading", refused(() -> HeliadLibrary.files(loc, "sdo|aia|193")));
        sql(db, "DELETE FROM meta WHERE key = 'reader_compat'");
        expect("an index without reader_compat reads as 1", HeliadLibrary.datasets(loc, from, to).size() == 3);

        // -- the link that hands a search to the Heliad app ------------------------------------
        long s = t("2025-09-21T16:00:00"), e = t("2025-09-21T16:05:00");
        expect("the link is written as Heliad's Query.to_url writes it",
                HeliadLibrary.link(s, e, aia).equals("heliad://search?end=2025-09-21T16%3A05%3A00&instrument=AIA"
                        + "&start=2025-09-21T16%3A00%3A00&wavelength=193"));
        expect("with no dataset it carries the range alone",
                HeliadLibrary.link(s, e, null).equals("heliad://search?end=2025-09-21T16%3A05%3A00&start=2025-09-21T16%3A00%3A00"));
        expect("and widens a range to whole seconds",
                HeliadLibrary.link(s + 500, e + 1, null).equals("heliad://search?end=2025-09-21T16%3A05%3A01&start=2025-09-21T16%3A00%3A00"));
        HeliadLibrary.Dataset fsi = new HeliadLibrary.Dataset("k", "l", "EUI", 30.4, 1, 1, 0, 0, 0);
        expect("a fractional wavelength keeps its decimals", HeliadLibrary.link(s, e, fsi).endsWith("&wavelength=30.4"));

        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
        System.out.println("all ok");
    }

    private interface Read {
        void run() throws Exception;
    }

    private static boolean refused(Read read) {
        try {
            read.run();
            return false;
        } catch (HeliadLibrary.TooNew e) {
            return e.getMessage().contains("reader version 2");
        } catch (Exception e) {
            return false;
        }
    }

}
