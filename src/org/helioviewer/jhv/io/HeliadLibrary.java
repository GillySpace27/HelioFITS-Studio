package org.helioviewer.jhv.io;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.json.JSONException;
import org.json.JSONObject;
import org.sqlite.SQLiteConfig;

/**
 * Read-only access to a Heliad library, for the Heliad tab of the New Image Layer dialog.
 *
 * <p>Heliad downloads and indexes FITS files into a library of its own and is the only program
 * that writes to it. This follows Heliad's reader contract (docs/reader-contract.md in the heliad
 * repository): find {@code config.json} in Heliad's folder, open the index it names read-only,
 * refuse an index whose {@code reader_compat} is newer than this reader, and read only
 * {@code current_files}, the newest version of each observation. Nothing here writes, creates or
 * caches anything. With no config file, or no index where it points, there is no library, and the
 * dialog stays exactly as it was before Heliad existed.
 *
 * <p>Every read opens the index, reads, and closes it again. Heliad keeps writing while Studio
 * runs, and an open read holds a snapshot that stops its log from being checkpointed, so nothing
 * is held between dialog openings and no path is trusted past the read that produced it.
 */
public final class HeliadLibrary {

    /** The newest index layout this reader understands: the contract's {@code reader_compat}. */
    public static final int READER_COMPAT = 1;

    /** The index's own file name, for a config that names only the library folder. */
    private static final String DB_NAME = "heliad.sqlite";

    /**
     * {@code date_obs} as Heliad writes it: ISO 8601 UTC with milliseconds (astropy's isot). The
     * range bounds are written the same way, so comparing the text compares the times, which is
     * what lets the index answer "what is in the range" without parsing a row.
     */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final DateTimeFormatter LINK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Where the library is: its folder, which relpaths resolve against, and its index. */
    public record Location(Path root, Path db) {}

    /**
     * One dataset (an instrument, channel and product level) as the master range sees it.
     *
     * <p>{@code start} and {@code end} are the first and last observation in epoch milliseconds,
     * or {@link Long#MIN_VALUE} when the index holds a time this cannot read; such a dataset is
     * still listed, so nothing in the library goes missing from the list. {@code inRange} is the
     * number of observations inside the range asked about, ends included.
     */
    public record Dataset(String key, String label, @Nullable String instrument, @Nullable Double wavelength,
                          int frames, long bytes, long start, long end, int inRange) {}

    /** The library was written by a Heliad newer than this reader: read nothing from it. */
    public static final class TooNew extends Exception {
        TooNew(int compat) {
            super("This Heliad library needs a newer HelioFITS Studio (reader version " + compat
                    + ", Studio reads " + READER_COMPAT + ")");
        }
    }

    private HeliadLibrary() {}

    /** The library on this machine, or null when Heliad has not made one. */
    @Nullable
    public static Location find() {
        Path dir = configDir(System.getenv("HELIAD_HOME"), System.getProperty("os.name", ""),
                Path.of(System.getProperty("user.home")), System.getenv("APPDATA"), System.getenv("XDG_CONFIG_HOME"));
        return read(dir);
    }

    /**
     * Heliad's folder for {@code config.json}, by the same rules Heliad uses to write it:
     * {@code HELIAD_HOME} when set, otherwise each platform's per-user settings folder.
     */
    static Path configDir(@Nullable String heliadHome, String osName, Path home,
                          @Nullable String appData, @Nullable String xdgConfigHome) {
        if (heliadHome != null && !heliadHome.isEmpty())
            return expand(heliadHome, home);
        if (osName.startsWith("Mac"))
            return home.resolve("Library").resolve("Application Support").resolve("Heliad");
        if (osName.startsWith("Windows"))
            return (appData == null || appData.isEmpty() ? home.resolve("AppData").resolve("Roaming") : Path.of(appData)).resolve("Heliad");
        return (xdgConfigHome == null || xdgConfigHome.isEmpty() ? home.resolve(".config") : Path.of(xdgConfigHome)).resolve("heliad");
    }

    /** The library a config folder points at, or null when there is no config or no index. */
    @Nullable
    public static Location read(Path configDir) {
        Path config = configDir.resolve("config.json");
        if (!Files.isRegularFile(config))
            return null;
        try {
            JSONObject json = new JSONObject(Files.readString(config));
            String rootText = json.optString("library_root", "");
            if (rootText.isEmpty())
                return null;
            Path home = Path.of(System.getProperty("user.home"));
            Path root = expand(rootText, home);
            String dbText = json.optString("db_path", "");
            Path db = dbText.isEmpty() ? root.resolve(DB_NAME) : expand(dbText, home);
            return Files.isRegularFile(db) ? new Location(root, db) : null;
        } catch (IOException | JSONException e) {
            Log.warn("Heliad config unreadable: " + config, e);
            return null;
        }
    }

    private static Path expand(String path, Path home) {
        if (path.equals("~"))
            return home;
        if (path.startsWith("~/") || path.startsWith("~\\"))
            return home.resolve(path.substring(2));
        return Path.of(path);
    }

    /** Every dataset in the library, with how many of its observations fall in [rangeStart, rangeEnd]. */
    public static List<Dataset> datasets(Location lib, long rangeStart, long rangeEnd) throws SQLException, TooNew {
        String sql = """
                SELECT dataset_key, MIN(label), MIN(instrument), MIN(wavelength), COUNT(*), SUM(size),
                       MIN(date_obs), MAX(date_obs), SUM(CASE WHEN date_obs >= ? AND date_obs <= ? THEN 1 ELSE 0 END)
                FROM current_files GROUP BY dataset_key ORDER BY MIN(label), dataset_key""";
        List<Dataset> out = new ArrayList<>();
        try (Connection conn = open(lib); PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, iso(rangeStart));
            st.setString(2, iso(rangeEnd));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    double wavelength = rs.getDouble(4);
                    Double w = rs.wasNull() ? null : wavelength;
                    out.add(new Dataset(rs.getString(1), rs.getString(2), rs.getString(3), w, rs.getInt(5), rs.getLong(6),
                            millis(rs.getString(7)), millis(rs.getString(8)), rs.getInt(9)));
                }
            }
        }
        return out;
    }

    /** The files of one dataset in [rangeStart, rangeEnd], in time order. */
    public static List<File> files(Location lib, String key, long rangeStart, long rangeEnd) throws SQLException, TooNew {
        return files(lib, "SELECT relpath FROM current_files WHERE dataset_key = ? AND date_obs >= ? AND date_obs <= ? "
                + "ORDER BY date_obs, file_id", key, iso(rangeStart), iso(rangeEnd));
    }

    /** Every file of one dataset, in time order. */
    public static List<File> files(Location lib, String key) throws SQLException, TooNew {
        return files(lib, "SELECT relpath FROM current_files WHERE dataset_key = ? ORDER BY date_obs, file_id", key);
    }

    private static List<File> files(Location lib, String sql, String... params) throws SQLException, TooNew {
        List<File> out = new ArrayList<>();
        try (Connection conn = open(lib); PreparedStatement st = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++)
                st.setString(i + 1, params[i]);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next())
                    out.add(lib.root().resolve(rs.getString(1)).toFile()); // always through relpath, never a view folder
            }
        }
        return out;
    }

    /** The index, read-only, after checking this reader may read it. */
    private static Connection open(Location lib) throws SQLException, TooNew {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(5000);
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + lib.db(), config.toProperties());
        try {
            int compat = readerCompat(conn);
            if (compat > READER_COMPAT)
                throw new TooNew(compat);
            return conn;
        } catch (SQLException | TooNew e) {
            try {
                conn.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    // Not schema_version: the schema grows without readers noticing, and only this changes when it would break them.
    private static int readerCompat(Connection conn) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("SELECT value FROM meta WHERE key = 'reader_compat'");
             ResultSet rs = st.executeQuery()) {
            return rs.next() ? Integer.parseInt(rs.getString(1).trim()) : 1; // absent means 1
        } catch (NumberFormatException e) {
            throw new SQLException("unreadable reader_compat", e);
        }
    }

    static String iso(long millis) {
        return ISO.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC));
    }

    static long millis(@Nullable String dateObs) {
        if (dateObs == null)
            return Long.MIN_VALUE;
        String text = dateObs.endsWith("Z") ? dateObs.substring(0, dateObs.length() - 1) : dateObs;
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * A {@code heliad://search} link that opens the Heliad app's search with this range and, when
     * a dataset is given, its instrument and wavelength filled in. Parameters are sorted and
     * encoded as Heliad's {@code Query.to_url} writes them, so the same search gives the same link
     * from either side. The range is widened to whole seconds.
     */
    public static String link(long rangeStart, long rangeEnd, @Nullable Dataset set) {
        StringBuilder url = new StringBuilder("heliad://search?end=").append(encode(linkTime(rangeEnd + 999)));
        if (set != null && set.instrument() != null && !set.instrument().isEmpty())
            url.append("&instrument=").append(encode(set.instrument()));
        url.append("&start=").append(encode(linkTime(rangeStart)));
        if (set != null && set.wavelength() != null)
            url.append("&wavelength=").append(BigDecimal.valueOf(set.wavelength()).stripTrailingZeros().toPlainString());
        return url.toString();
    }

    private static String linkTime(long millis) {
        return LINK_TIME.format(Instant.ofEpochMilli(Math.floorDiv(millis, 1000L) * 1000L).atOffset(ZoneOffset.UTC));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The installed Heliad app on macOS, which opens heliad:// links, or null. */
    @Nullable
    public static Path app() {
        if (!System.getProperty("os.name", "").startsWith("Mac"))
            return null;
        for (Path dir : List.of(Path.of("/Applications"), Path.of(System.getProperty("user.home"), "Applications"))) {
            Path app = dir.resolve("Heliad.app");
            if (Files.isDirectory(app))
                return app;
        }
        return null;
    }

}
