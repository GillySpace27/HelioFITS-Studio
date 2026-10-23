package org.helioviewer.jhv.io;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.json.JSONObject;

/**
 * A local source frame is named in provenance by its content, never its path: the SHA-256 of a
 * file outside any library, and Heliad's own sha256, dataset_key, obs_key and (schema 5)
 * dataset_uid for a file in one. Also the dataset lookup behind -dataset and heliad.dataset.load.
 *
 * <p>The indexes are small SQLite files built here: one with Heliad's reader_compat 1 columns
 * only, one that adds a dataset_uid column to datasets as schema 5 is expected to. user.home is
 * moved to a temp folder first (the log and the app folders hang off it).
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.io.SourceIdentityCheck
 */
public final class SourceIdentityCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
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
            INSERT INTO meta VALUES ('schema_version', '4'), ('reader_compat', '1');
            INSERT INTO datasets VALUES (1, 'sdo/aia/-/-/1/193', 'SDO', 'AIA', NULL, NULL, '1', 193.0, 'SDO AIA 193 L1');
            INSERT INTO observations VALUES (1, 'sdo/aia/-/-/1/193|2025-09-21T16:00:04.840', 1, '2025-09-21T16:00:04.840', 1),
                                            (2, 'sdo/aia/-/-/1/193|2025-09-21T16:01:00.000', 1, '2025-09-21T16:01:00.000', 3),
                                            (3, 'sdo/aia/-/-/1/193|2025-09-21T16:02:00.000', 1, '2025-09-21T16:02:00.000', 4);
            INSERT INTO files VALUES (1, '%s', 'store/aia/a1.fits', 3, 1, NULL),
                                     (2, 'old2', 'store/aia/a2_v1.fits', 3, 2, '1'),
                                     (3, 'new2', 'store/aia/a2_v2.fits', 3, 2, '2'),
                                     (4, 'gone3', 'store/aia/a3.fits', 3, 3, NULL);
            """;

    private static final String SCHEMA_5 = """
            ALTER TABLE datasets ADD COLUMN dataset_uid TEXT;
            UPDATE datasets SET dataset_uid = 'ds-7f3a' WHERE id = 1;
            CREATE TABLE dataset_aliases (old_key TEXT PRIMARY KEY, dataset_uid TEXT NOT NULL, recorded_at TEXT);
            INSERT INTO dataset_aliases VALUES ('sdo/aia/-/-/1/195', 'ds-7f3a', NULL);
            """;

    private static void sql(Path db, String statements) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = conn.createStatement()) {
            for (String one : statements.split(";"))
                if (!one.isBlank())
                    st.executeUpdate(one);
        }
    }

    private static long t(String iso) {
        return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("source-identity-check");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString());

        // The SHA-256 of "abc" is the FIPS 180-2 test vector.
        String abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        Path loose = tmp.resolve("loose.fits");
        Files.writeString(loose, "abc", StandardCharsets.US_ASCII);
        expect("sha256 of a file is its content hash (FIPS 180-2 'abc')", abc.equals(SourceIdentity.sha256(loose)));

        // -- a library without dataset_uid (reader_compat 1, schema 4) -----------------------
        Path root = Files.createDirectories(tmp.resolve("library"));
        Files.createDirectories(root.resolve("store/aia"));
        for (String name : List.of("a1.fits", "a2_v1.fits", "a2_v2.fits"))
            Files.writeString(root.resolve("store/aia").resolve(name), "abc", StandardCharsets.US_ASCII); // a3.fits is missing on disk
        Path db = root.resolve("heliad.sqlite");
        sql(db, String.format(SCHEMA, "s1"));
        HeliadLibrary.Location lib = new HeliadLibrary.Location(root, db);

        HeliadLibrary.Identity a1 = HeliadLibrary.identity(lib, "store/aia/a1.fits");
        expect("a current library file gives Heliad's sha256, not one computed here", a1 != null && "s1".equals(a1.sha256()));
        expect("and its dataset_key and obs_key", a1 != null && "sdo/aia/-/-/1/193".equals(a1.datasetKey())
                && "sdo/aia/-/-/1/193|2025-09-21T16:00:04.840".equals(a1.obsKey()));
        expect("no dataset_uid column, no dataset_uid", a1 != null && a1.datasetUid() == null);
        expect("a superseded version is not a current file", HeliadLibrary.identity(lib, "store/aia/a2_v1.fits") == null);
        expect("a relpath the library does not hold is null", HeliadLibrary.identity(lib, "store/other.fits") == null);

        HeliadLibrary.Identity viaPath = SourceIdentity.heliad(root.resolve("store/aia/a1.fits"), lib);
        expect("a file under the library root is looked up by its relpath", viaPath != null && "s1".equals(viaPath.sha256()));
        expect("a file outside the root is not the library's", SourceIdentity.heliad(loose, lib) == null);
        expect("no library, no Heliad identity", SourceIdentity.heliad(root.resolve("store/aia/a1.fits"), null) == null);

        SourceIdentity.Entry entry = new SourceIdentity.Entry(a1.sha256(), 3, a1);
        JSONObject json = entry.toJson();
        expect("the provenance form has sha256, dataset_key and obs_key, and nothing else here: " + json,
                json.keySet().equals(java.util.Set.of("sha256", "dataset_key", "obs_key")));
        expect("and no path", !json.toString().contains(root.toString()) && !json.toString().contains("store/"));
        expect("a file outside any library is sha256 alone",
                new SourceIdentity.Entry(abc, 3, null).toJson().keySet().equals(java.util.Set.of("sha256")));

        SourceIdentity.Entry looked = SourceIdentity.lookup(loose.toUri(), 10_000);
        expect("lookup hashes a local file that is in no library", looked != null && abc.equals(looked.sha256()) && looked.heliad() == null);
        expect("an archive address has no local identity", SourceIdentity.lookup(URI.create("https://example.org/a.fits"), 1000) == null);
        expect("a missing file has none", SourceIdentity.lookup(tmp.resolve("missing.fits").toUri(), 1000) == null);
        SourceIdentity.prefetch(List.of(loose.toUri()));
        expect("a known file answers at once without waiting", SourceIdentity.lookup(loose.toUri(), 0) != null);

        // -- the dataset lookup behind -dataset and heliad.dataset.load ------------------------
        expect("a dataset_key names its dataset", "sdo/aia/-/-/1/193".equals(HeliadLibrary.datasetKey(lib, "sdo/aia/-/-/1/193")));
        expect("an unknown name names none", HeliadLibrary.datasetKey(lib, "ds-7f3a") == null);
        List<URI> all = HeliadDatasetLoad.files(lib, "sdo/aia/-/-/1/193", Long.MIN_VALUE, Long.MAX_VALUE);
        expect("an open range is every current file that exists, in time order",
                all.equals(List.of(root.resolve("store/aia/a1.fits").toUri(), root.resolve("store/aia/a2_v2.fits").toUri())));
        List<URI> range = HeliadDatasetLoad.files(lib, "sdo/aia/-/-/1/193", t("2025-09-21T16:00:30"), Long.MAX_VALUE);
        expect("a start drops what came before it", range.equals(List.of(root.resolve("store/aia/a2_v2.fits").toUri())));
        expect("nothing in the range is a failure, not an empty layer",
                fails(() -> HeliadDatasetLoad.files(lib, "sdo/aia/-/-/1/193", t("2025-09-21T16:01:30"), t("2025-09-21T16:03:00"))));
        expect("no library is a failure", fails(() -> HeliadDatasetLoad.files(null, "sdo/aia/-/-/1/193", Long.MIN_VALUE, Long.MAX_VALUE)));
        expect("no such dataset is a failure", fails(() -> HeliadDatasetLoad.files(lib, "nope", Long.MIN_VALUE, Long.MAX_VALUE)));
        expect("an ISO time with or without Z reads the same",
                HeliadDatasetLoad.parse("2025-09-21T16:00:30Z") == t("2025-09-21T16:00:30")
                        && HeliadDatasetLoad.parse("2025-09-21T16:00:30") == t("2025-09-21T16:00:30"));
        expect("fractional seconds as Heliad writes them (no Z, UTC)",
                HeliadDatasetLoad.parse("2025-09-21T16:00:04.840") == t("2025-09-21T16:00:04.840"));
        expect("a time that is not one is refused", fails(() -> HeliadDatasetLoad.parse("yesterday")));

        // -- schema 5: a dataset_uid column -------------------------------------------------
        sql(db, SCHEMA_5);
        HeliadLibrary.Identity withUid = HeliadLibrary.identity(lib, "store/aia/a1.fits");
        expect("with the column, the dataset_uid is read", withUid != null && "ds-7f3a".equals(withUid.datasetUid()));
        expect("and goes into the provenance form",
                new SourceIdentity.Entry(withUid.sha256(), 3, withUid).toJson().optString("dataset_uid").equals("ds-7f3a"));
        expect("a dataset_uid names its dataset", "sdo/aia/-/-/1/193".equals(HeliadLibrary.datasetKey(lib, "ds-7f3a")));
        expect("and a dataset_key still does", "sdo/aia/-/-/1/193".equals(HeliadLibrary.datasetKey(lib, "sdo/aia/-/-/1/193")));
        expect("a former key finds the dataset through dataset_aliases", "sdo/aia/-/-/1/193".equals(HeliadLibrary.datasetKey(lib, "sdo/aia/-/-/1/195")));
        expect("an unknown name still names none", HeliadLibrary.datasetKey(lib, "sdo/aia/-/-/1/211") == null);

        if (failures > 0) {
            System.out.println("SourceIdentityCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("SourceIdentityCheck: all passed");
        System.exit(0); // the identity worker thread is not a daemon
    }

    private interface Throwing {
        void run() throws Exception;
    }

    private static boolean fails(Throwing r) {
        try {
            r.run();
            return false;
        } catch (HeliadDatasetLoad.Failed e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
