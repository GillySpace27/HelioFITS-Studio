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
 * Reading a HelioFITS Core library the way its reader contract says to: where the config is on each
 * platform, what counts as no library, what the FITS library tab lists for a master range, which files
 * an Add loads, that an index from a newer core is refused, and that reading leaves no trace.
 *
 * <p>The index is a small SQLite file built here with the parts of HelioFITS Core's schema that
 * {@code current_files} reads, including a superseded file version, so a reader that looked at
 * the files table instead of the view would count it. Nothing outside a temp folder is touched:
 * {@code user.home} is moved there first, because the log and the app folders hang off it.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.io.FitsLibraryCheck
 */
public final class FitsLibraryCheck {

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
        Path tmp = Files.createTempDirectory("fits-library-check");
        Path fakeHome = Files.createDirectories(tmp.resolve("home"));
        System.setProperty("user.home", fakeHome.toString());

        // -- what a download asks for, against HelioFITS Core dataset keys (from a real library; the K-Cor one is made up) --
        expect("AIA 193 finds AIA 193", FitsLibrary.Wanted.of(null, "aia", null, 193).matches("sdo/aia/-/-/1/193"));
        expect("AIA 193 does not find AIA 304", !FitsLibrary.Wanted.of(null, "aia", null, 193).matches("sdo/aia/-/-/1/304"));
        expect("SECCHI COR2 finds COR2", FitsLibrary.Wanted.of(null, "secchi", "COR2", null).matches("stereo-a/secchi/cor2/-/-/-"));
        expect("SECCHI COR2 does not find COR1", !FitsLibrary.Wanted.of(null, "secchi", "COR2", null).matches("stereo-a/secchi/cor1/-/-/-"));
        expect("SECCHI EUVI 195 does not find EUVI 171",
                !FitsLibrary.Wanted.of(null, "secchi", "EUVI", 195).matches("stereo-a/secchi/euvi/-/-/171"));
        expect("PUNCH PTM L3 finds PTM, by level written L3",
                new FitsLibrary.Wanted("punch", null, null, "PTM", "L3", null).matches("punch/wfi+nfi-mosaic/-/ptm/3/530"));
        expect("PUNCH PTM does not find CTM", !new FitsLibrary.Wanted("punch", null, null, "PTM", "3", null).matches("punch/wfi+nfi-mosaic/-/ctm/3/530"));
        expect("K-Cor and kcor are one instrument", FitsLibrary.Wanted.of(null, "kcor", null, null).matches("mlso/k-cor/-/-/2/7350"));
        expect("a key of the wrong shape matches nothing", !FitsLibrary.Wanted.of(null, null, null, null).matches("sdo/aia"));
        FitsLibrary.Wanted soarFsi = org.helioviewer.jhv.gui.dialog.SoarDialog.wanted("solo_L2_eui-fsi174-image_20250921T160000180_V02");
        expect("a SOAR FSI 174 id finds EUI FSI 174 L2", soarFsi.matches("solar-orbiter/eui/fsi/-/2/174"));
        expect("and not FSI 304", !soarFsi.matches("solar-orbiter/eui/fsi/-/2/304"));
        expect("a SOAR SoloHI id finds SoloHI by instrument",
                org.helioviewer.jhv.gui.dialog.SoarDialog.wanted("solo_L2_solohi-1ft_20250921T160300_V01").matches("solar-orbiter/solohi/1/-/2/5400"));

        // -- where HelioFITS Core keeps config.json --------------------------------------------
        Path home = Path.of("/Users/someone");
        expect("macOS: ~/Library/Application Support/HelioFITS Core",
                FitsLibrary.configDir(null, "Mac OS X", home, null, null).equals(home.resolve("Library/Application Support/HelioFITS Core")));
        expect("Linux: ~/.config/hfcore when XDG_CONFIG_HOME is unset",
                FitsLibrary.configDir(null, "Linux", home, null, null).equals(home.resolve(".config/hfcore")));
        expect("Linux: XDG_CONFIG_HOME/hfcore when it is set",
                FitsLibrary.configDir(null, "Linux", home, null, "/xdg").equals(Path.of("/xdg/hfcore")));
        expect("Windows: %APPDATA%/HelioFITS Core",
                FitsLibrary.configDir(null, "Windows 11", home, "/roaming", null).equals(Path.of("/roaming/HelioFITS Core")));
        expect("Windows: ~/AppData/Roaming/HelioFITS Core without APPDATA",
                FitsLibrary.configDir(null, "Windows 11", home, null, null).equals(home.resolve("AppData/Roaming/HelioFITS Core")));
        expect("HFCORE_HOME replaces the folder on every platform",
                FitsLibrary.configDir("/elsewhere", "Mac OS X", home, null, null).equals(Path.of("/elsewhere"))
                        && FitsLibrary.configDir("/elsewhere", "Linux", home, null, "/xdg").equals(Path.of("/elsewhere")));
        expect("an empty HFCORE_HOME counts as unset",
                FitsLibrary.configDir("", "Linux", home, null, null).equals(home.resolve(".config/hfcore")));
        // A computer set up before the rename to HelioFITS Core (it was Heliad) keeps its folder.
        Path oldHome = tmp.resolve("old-home");
        Path oldDir = Files.createDirectories(oldHome.resolve("Library/Application Support/HelioFITS Core"));
        Files.writeString(oldDir.resolve("config.json"), "{}");
        expect("the old HelioFITS Core folder is used while the new one has no config",
                FitsLibrary.configDir(null, "Mac OS X", oldHome, null, null).equals(oldDir));
        Path newDir = Files.createDirectories(oldHome.resolve("Library/Application Support/HelioFITS Core"));
        Files.writeString(newDir.resolve("config.json"), "{}");
        expect("and the new one once it has a config",
                FitsLibrary.configDir(null, "Mac OS X", oldHome, null, null).equals(newDir));
        Path oldLib = Files.createDirectories(tmp.resolve("old-lib"));
        Files.writeString(oldLib.resolve(FitsLibrary.LEGACY_DB_NAME), "");
        expect("a library made before the rename keeps its heliad.sqlite", FitsLibrary.index(oldLib).equals(oldLib.resolve("heliad.sqlite")));
        expect("a new library's index is hfcore.sqlite", FitsLibrary.index(tmp.resolve("new-lib")).equals(tmp.resolve("new-lib/hfcore.sqlite")));

        // -- what counts as no library -------------------------------------------------------
        Path cfg = tmp.resolve("cfg");
        expect("no config folder is no library", FitsLibrary.read(cfg) == null);
        Files.createDirectories(cfg);
        expect("an empty config folder is no library", FitsLibrary.read(cfg) == null);
        Path root = Files.createDirectories(tmp.resolve("library"));
        Path db = root.resolve("hfcore.sqlite");
        Files.writeString(cfg.resolve("config.json"), config(root, db));
        expect("a config whose index is not there is no library, and nothing is created",
                FitsLibrary.read(cfg) == null && !Files.exists(db));
        sql(db, SCHEMA);
        FitsLibrary.Location loc = FitsLibrary.read(cfg);
        expect("a config and its index are a library", loc != null && loc.root().equals(root) && loc.db().equals(db));
        Files.writeString(cfg.resolve("config.json"), new JSONObject().put("library_root", root.toString()).toString());
        FitsLibrary.Location rootOnly = FitsLibrary.read(cfg);
        expect("a config naming only the folder finds hfcore.sqlite in it", rootOnly != null && rootOnly.db().equals(db));
        Path tilde = Files.createDirectories(fakeHome.resolve("lib2"));
        Files.copy(db, tilde.resolve("hfcore.sqlite"));
        Files.writeString(cfg.resolve("config.json"), "{\"library_root\": \"~/lib2\", \"db_path\": \"~/lib2/hfcore.sqlite\"}");
        FitsLibrary.Location expanded = FitsLibrary.read(cfg);
        expect("~ in the config is the home folder", expanded != null && expanded.root().equals(tilde));
        Files.writeString(cfg.resolve("config.json"), "{\"library_root\": ");
        expect("a config that is not JSON is no library rather than an error", FitsLibrary.read(cfg) == null);
        Files.writeString(cfg.resolve("config.json"), config(root, db));

        // -- the datasets the tab lists ------------------------------------------------------
        List<Path> before = listing(root);
        byte[] bytesBefore = Files.readAllBytes(db);
        long from = t("2025-09-21T16:01:00"), to = t("2025-09-21T16:03:00");
        List<FitsLibrary.Dataset> sets = FitsLibrary.datasets(loc, from, to);
        expect("every dataset is listed, by label",
                sets.stream().map(FitsLibrary.Dataset::label).toList().equals(List.of("Odd", "PUNCH WFI L3", "SDO AIA 193 L1")));
        FitsLibrary.Dataset aia = sets.get(2);
        expect("frames count observations, not file versions", aia.frames() == 5);
        expect("bytes are the current versions' sizes", aia.bytes() == 5000);
        expect("observations on both ends of the range are in it", aia.inRange() == 3);
        expect("cadence is the median gap (gaps 55.16, 64.84, 55.16, 64.84 s give the lower middle)", aia.cadence() == 55_160);
        expect("a two-frame dataset's cadence is its one gap", sets.get(1).cadence() == 240_000);
        expect("unreadable times give no cadence", sets.get(0).cadence() == 0);
        expect("the attributes come through for the columns",
                "SDO".equals(aia.observatory()) && "1".equals(aia.level()) && aia.detector() == null);
        expect("the span is the first and last observation",
                aia.start() == t("2025-09-21T16:00:04.840") && aia.end() == t("2025-09-21T16:04:04.840"));
        expect("instrument and wavelength come through for the HelioFITS Core link",
                "AIA".equals(aia.instrument()) && Double.valueOf(193.0).equals(aia.wavelength()));
        FitsLibrary.Dataset punch = sets.get(1);
        expect("a dataset outside the range is listed with nothing in it", punch.inRange() == 0 && punch.frames() == 2);
        expect("a missing wavelength stays missing rather than reading as 0", punch.wavelength() == null);
        FitsLibrary.Dataset odd = sets.getFirst();
        expect("a time the reader cannot parse keeps the dataset listed, its span unknown",
                odd.frames() == 1 && odd.start() == Long.MIN_VALUE && odd.end() == Long.MIN_VALUE && odd.inRange() == 0);

        // -- the files an Add loads ----------------------------------------------------------
        List<File> inRange = FitsLibrary.files(loc, "sdo|aia|193", from, to);
        expect("an Add loads the in-range files in time order, resolved under the library",
                inRange.equals(Stream.of("store/aia/a2.fits", "store/aia/a3_v2.fits", "store/aia/a4.fits")
                        .map(p -> root.resolve(p).toFile()).toList()));
        expect("the whole dataset is every current file", FitsLibrary.files(loc, "sdo|aia|193").size() == 5);
        expect("a range with nothing in it loads nothing", FitsLibrary.files(loc, "punch|wfi", from, to).isEmpty());
        expect("reading created nothing beside the index", listing(root).equals(before));
        expect("and left the index as it was", Arrays.equals(Files.readAllBytes(db), bytesBefore));

        // -- reader_compat, which is the only version a reader may look at ---------------------
        sql(db, "UPDATE meta SET value = '99' WHERE key = 'schema_version'");
        expect("a newer schema_version alone does not stop the reader", FitsLibrary.datasets(loc, from, to).size() == 3);
        sql(db, "UPDATE meta SET value = '2' WHERE key = 'reader_compat'");
        expect("an index from a newer HelioFITS Core is refused when listing", refused(() -> FitsLibrary.datasets(loc, from, to)));
        expect("and when loading", refused(() -> FitsLibrary.files(loc, "sdo|aia|193")));
        sql(db, "DELETE FROM meta WHERE key = 'reader_compat'");
        expect("an index without reader_compat reads as 1", FitsLibrary.datasets(loc, from, to).size() == 3);

        // -- the link that hands a search to HelioFITS Cache Manager ---------------------------
        long s = t("2025-09-21T16:00:00"), e = t("2025-09-21T16:05:00");
        expect("the link is written as the core's Query.to_url writes it",
                FitsLibrary.link("hfcore", s, e, aia).equals("hfcore://search?end=2025-09-21T16%3A05%3A00&instrument=AIA"
                        + "&start=2025-09-21T16%3A00%3A00&wavelength=193"));
        expect("with no dataset it carries the range alone",
                FitsLibrary.link("hfcore", s, e, null).equals("hfcore://search?end=2025-09-21T16%3A05%3A00&start=2025-09-21T16%3A00%3A00"));
        expect("and widens a range to whole seconds",
                FitsLibrary.link("hfcore", s + 500, e + 1, null).equals("hfcore://search?end=2025-09-21T16%3A05%3A01&start=2025-09-21T16%3A00%3A00"));
        expect("an app from before the rename gets its own scheme",
                FitsLibrary.link("heliad", s, e, null).startsWith("heliad://search?end="));
        expect("with no app installed here the link uses the new scheme",
                FitsLibrary.app() != null || FitsLibrary.link(s, e, null).startsWith("hfcore://search?"));
        FitsLibrary.Dataset fsi = new FitsLibrary.Dataset("k", "l", null, "EUI", null, null, null, 30.4, 1, 1, 0, 0, 0, 0);
        expect("a fractional wavelength keeps its decimals", FitsLibrary.link(s, e, fsi).endsWith("&wavelength=30.4"));

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
        } catch (FitsLibrary.TooNew e) {
            return e.getMessage().contains("reader version 2");
        } catch (Exception e) {
            return false;
        }
    }

}
