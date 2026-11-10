package org.helioviewer.jhv.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.helioviewer.jhv.gui.registry.KeyBindings;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.StorageFolders;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Save and load the user's global preferences: one human-readable, versioned JSON file, so a
 * reinstall or a new computer keeps the configuration (Gilly, 2026-11-08).
 *
 * <p>Everything a person sets lives in one flat table, {@code user.properties} ({@link Settings}):
 * the Settings dialog, keybindings ({@code keys.*}), Canvas keys ({@code canvasKeys.*}), the
 * interface scales, the toolbar, sidebar and palette layout ({@code ui.*}), Identify, the load guard
 * limits and the rest. This class exports the part of that table that is meant to travel.
 *
 * <p><b>What travels is an allow-list, not a deny-list</b> ({@link #classify}): a setting added
 * later does not travel until it is named here, and a secret added later can never leak by default.
 * Three kinds of key do not travel in the default export:
 * <ul>
 * <li>secrets and identities (proxy login, the install id, the local API switch; the API key itself
 * is in {@code api.json}, a file this class never reads);</li>
 * <li>things that belong to one machine or one moment (window bounds, which screen is the output,
 * the time of the last update check);</li>
 * <li>paths ({@link Kind#PATH}): travel only when the person ticks "include folder paths", because a
 * folder on the old computer is rarely one on the new. A path-valued setting is recognised by its
 * value as well as its name, so one nobody classified is held back too.</li>
 * </ul>
 *
 * <p>Loading is a plan first ({@link #plan}), then a confirm, then {@link #apply}: the file is
 * checked for its format and version, keys this build does not know are ignored, each value is
 * checked for its type and range, and what would change is listed. Nothing is deleted: a key the file
 * does not mention is left as it is.
 */
public final class GlobalPreferences {

    public static final String FORMAT = "HelioFITS Studio global preferences";
    /** Bump on a change a version-1 reader would misread; additions of keys need no bump. */
    public static final int VERSION = 1;
    /** Whether Studio keeps a dated copy of these preferences when it quits. On unless switched off. */
    public static final String BACKUP_KEY = "preferences.backupOnExit";

    public enum Kind { SETTING, PATH, SKIP }

    /** One setting the load would write: {@code from} is null when the key is not set now. */
    public record Change(String key, String from, String to, Kind kind) {}

    /**
     * What loading a file would do. {@code held} are path-valued entries left out because the person
     * did not include paths; {@code ignored} are keys this build does not carry; {@code rejected} are
     * keys whose value failed its check, each with the reason.
     */
    public record Plan(int version, String exportedBy, String exportedAt, List<Change> changes, int unchanged,
            List<String> held, List<String> ignored, List<String> rejected) {

        public boolean isEmpty() {
            return changes.isEmpty();
        }

        /** The text of the confirm step: counts first, then every change, then what was left out. */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("File: format version ").append(version);
            if (!exportedBy.isEmpty())
                sb.append(", saved by Studio ").append(exportedBy);
            if (!exportedAt.isEmpty())
                sb.append(" at ").append(exportedAt);
            sb.append('\n').append(changes.size()).append(" setting(s) would change, ").append(unchanged).append(" already match");
            if (!held.isEmpty())
                sb.append(", ").append(held.size()).append(" folder path(s) held back");
            if (!ignored.isEmpty())
                sb.append(", ").append(ignored.size()).append(" unknown or not-for-transfer key(s) ignored");
            if (!rejected.isEmpty())
                sb.append(", ").append(rejected.size()).append(" rejected");
            sb.append(".\n");
            for (Change c : changes)
                sb.append("\n  ").append(c.key()).append(": ").append(show(c.from())).append("  ->  ").append(show(c.to()));
            for (String s : rejected)
                sb.append("\n  rejected ").append(s);
            for (String s : held)
                sb.append("\n  held back (path) ").append(s);
            return sb.toString();
        }

        private static String show(String v) {
            if (v == null)
                return "(not set)";
            String s = v.replace("\n", "\\n");
            return s.isEmpty() ? "(default)" : s.length() > 70 ? s.substring(0, 67) + "..." : s;
        }
    }

    private static final int MAX_VALUE = 8192;
    private static final Pattern KEY_OK = Pattern.compile("[A-Za-z0-9_.+\\-]{1,200}");
    private static final Pattern ABSOLUTE = Pattern.compile("^(/|~|[A-Za-z]:[\\\\/]|\\\\\\\\).*");

    /** Prefixes and exact keys that may travel. Order does not matter. */
    private static final String[] ALLOW_PREFIX = {
            "display.", "ui.", "keys.", "canvasKeys.", "identify.", "load.", "study.", "video.", "activity.", "playback.",
            "player.", "plugins.", "export.", "preferences.", "presentation.keep", "api.glow", "record."};
    private static final java.util.Set<String> ALLOW_EXACT = java.util.Set.of(
            "dataSources.defaultServer", "startup.sampHub", "startup.mode", "startup.welcome", "startup.welcomeSeen", "startup.welcomeVersion",
            "session.confirmOnExit", "tips.atStartup", "tips.seen", "update.checkAtStartup", "api.confirmDestructive",
            "fitslibrary.hiddenColumns", "path.local", "path.state", "startup.loadState", "session.mainFile", "recentSessions");
    /** Allowed prefixes with exceptions: bound to one screen or one moment. */
    private static final java.util.Set<String> NEVER = java.util.Set.of(
            "ui.windowBounds", "display.persistedBounds", "presentation.outputScreen", "presentation.controlsScreen");
    private static final java.util.Set<String> PATH_KEYS = java.util.Set.of(
            "path.local", "path.state", "startup.loadState", "session.mainFile", "recentSessions");

    /** The storage folders, which live in storage.properties, not in the settings table. */
    private static final String[] STORAGE = {StorageFolders.EXPORTS, StorageFolders.FILECACHE};
    private static final String STORAGE_PREFIX = "storage.";

    private GlobalPreferences() {}

    // ---- what travels ----

    /** Whether {@code key} with {@code value} is a setting to carry, a path to carry on request, or neither. */
    public static Kind classify(String key, String value) {
        if (!KEY_OK.matcher(key).matches() || NEVER.contains(key))
            return Kind.SKIP;
        if (key.startsWith("ui.section.") && !Settings.isLayoutKey(key))
            return Kind.SKIP; // a section named after a layer follows the data, not the layout
        if (!ALLOW_EXACT.contains(key)) {
            boolean ok = false;
            for (String p : ALLOW_PREFIX)
                ok |= key.startsWith(p);
            if (!ok)
                return Kind.SKIP;
        }
        if (PATH_KEYS.contains(key) || looksLikePath(value))
            return Kind.PATH;
        return Kind.SETTING;
    }

    private static boolean looksLikePath(String value) {
        if (value == null)
            return false;
        for (String line : value.split("\n"))
            if (ABSOLUTE.matcher(line.strip()).matches())
                return true;
        return false;
    }

    // ---- save ----

    /**
     * The file's text. {@code settings} is the person's own table ({@link Settings#own}), so a value
     * still at its default is not written and keeps following the default in the new build.
     */
    public static String export(Map<String, String> settings, Map<String, String> storage, boolean includePaths, String appVersion, Instant when) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : settings.entrySet()) {
            Kind k = classify(e.getKey(), e.getValue());
            if (k == Kind.SETTING || (k == Kind.PATH && includePaths))
                out.put(e.getKey(), e.getValue());
        }
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"format\": ").append(JSONObject.quote(FORMAT)).append(",\n");
        sb.append("  \"version\": ").append(VERSION).append(",\n");
        sb.append("  \"app\": ").append(JSONObject.quote(appVersion)).append(",\n");
        sb.append("  \"exportedAt\": ").append(JSONObject.quote(when.toString())).append(",\n");
        sb.append("  \"includesPaths\": ").append(includePaths).append(",\n");
        sb.append("  \"note\": ").append(JSONObject.quote("Settings only: no passwords, tokens, api.json or window positions. "
                + "Load it with File > Load Global Preferences. Keys this version does not know are ignored.")).append(",\n");
        sb.append("  \"settings\": ").append(object(out, "  "));
        if (includePaths && !storage.isEmpty())
            sb.append(",\n  \"storageFolders\": ").append(object(new TreeMap<>(storage), "  "));
        return sb.append("\n}\n").toString();
    }

    private static String object(Map<String, String> m, String indent) {
        if (m.isEmpty())
            return "{}";
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : m.entrySet())
            sb.append(indent).append("  ").append(JSONObject.quote(e.getKey())).append(": ").append(JSONObject.quote(e.getValue()))
                    .append(++i < m.size() ? ",\n" : "\n");
        return sb.append(indent).append('}').toString();
    }

    /** Write the live preferences to {@code file}, through a temporary file so a failure leaves nothing half written. */
    public static void save(Path file, boolean includePaths) throws IOException {
        writeAtomically(file, export(Settings.own(), storageFolders(), includePaths, AppInfo.version, Instant.now()));
    }

    private static Map<String, String> storageFolders() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : STORAGE) {
            String chosen = StorageFolders.chosen(k);
            if (chosen != null)
                m.put(STORAGE_PREFIX + k, chosen);
        }
        return m;
    }

    private static void writeAtomically(Path file, String text) throws IOException {
        if (file.getParent() != null)
            Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The copy kept when Studio quits: {@code Backups/preferences-<date>.json}, one per day (the
     * day's last quit wins), older ones left alone. It includes paths, because it is for this
     * computer; it never holds a secret. Never a reason to fail a quit.
     */
    public static void backupOnExit() {
        try {
            if ("false".equals(Settings.getProperty(BACKUP_KEY)))
                return;
            if (Settings.own().isEmpty())
                return; // nothing was read or set: do not replace a good day's copy with an empty one
            save(Path.of(Directories.BACKUPS.getPath(), "preferences-" + LocalDate.now() + ".json"), true);
        } catch (IOException | RuntimeException e) {
            Log.warn(e);
        }
    }

    // ---- load ----

    /**
     * Read {@code json} against the settings now in force and say what loading would do.
     *
     * @throws IllegalArgumentException with a sentence for the person when the file is not usable as
     *                                  a whole (not JSON, not ours, or from a newer format)
     */
    public static Plan plan(String json, Map<String, String> current, Map<String, String> currentStorage, boolean includePaths) {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new IllegalArgumentException("This is not a preferences file (it is not valid JSON).");
        }
        if (!FORMAT.equals(root.optString("format")))
            throw new IllegalArgumentException("This is not a " + FORMAT + " file.");
        int version = root.optInt("version", -1);
        if (version < 1)
            throw new IllegalArgumentException("The file has no usable version number.");
        if (version > VERSION)
            throw new IllegalArgumentException("The file is format version " + version + "; this Studio reads up to " + VERSION + ". Update Studio to load it.");
        JSONObject settings = root.optJSONObject("settings");
        if (settings == null)
            throw new IllegalArgumentException("The file has no \"settings\" section.");

        List<Change> changes = new ArrayList<>();
        List<String> held = new ArrayList<>(), ignored = new ArrayList<>(), rejected = new ArrayList<>();
        int[] same = {0};
        for (String key : new TreeMap<>(settings.toMap()).keySet())
            consider(key, settings.get(key), classify(key, ""), current.get(key), includePaths, changes, held, ignored, rejected, same);
        JSONObject storage = root.optJSONObject("storageFolders");
        if (storage != null)
            for (String key : new TreeMap<>(storage.toMap()).keySet())
                consider(key, storage.get(key), key.startsWith(STORAGE_PREFIX) && isStorageName(key) ? Kind.PATH : Kind.SKIP,
                        currentStorage.get(key), includePaths, changes, held, ignored, rejected, same);
        return new Plan(version, root.optString("app"), root.optString("exportedAt"), changes, same[0], held, ignored, rejected);
    }

    private static boolean isStorageName(String key) {
        for (String k : STORAGE)
            if (key.equals(STORAGE_PREFIX + k))
                return true;
        return false;
    }

    private static void consider(String key, Object raw, Kind byName, String now, boolean includePaths,
            List<Change> changes, List<String> held, List<String> ignored, List<String> rejected, int[] same) {
        if (!(raw instanceof String value)) {
            rejected.add(key + ": the value is not text");
            return;
        }
        Kind kind = byName == Kind.SETTING && looksLikePath(value) ? Kind.PATH : byName;
        if (kind == Kind.SKIP) {
            ignored.add(key);
            return;
        }
        String problem = check(key, value, now);
        if (problem != null) {
            rejected.add(key + ": " + problem);
            return;
        }
        if (kind == Kind.PATH && !includePaths) {
            held.add(key + " = " + value);
            return;
        }
        if (value.equals(now)) {
            same[0]++;
            return;
        }
        changes.add(new Change(key, now, value, kind));
    }

    private static boolean isBool(String v) {
        return v != null && (v.equals("true") || v.equals("false"));
    }

    private static boolean isNumber(String v) {
        try {
            return Double.isFinite(Double.parseDouble(v.strip()));
        } catch (NumberFormatException | NullPointerException e) {
            return false;
        }
    }

    /** Ranges of the numbers whose bounds are known; a number outside is refused, not clamped. */
    private static final Map<String, double[]> RANGE = Map.ofEntries(
            Map.entry("load.confirmFrames", new double[]{0, 10_000_000}), Map.entry("load.confirmMB", new double[]{0, 10_000_000}),
            Map.entry("display.uiScale", new double[]{0.25, 5}), Map.entry("display.textScale", new double[]{0.25, 5}),
            Map.entry("display.headingScale", new double[]{0.25, 5}), Map.entry("display.detailScale", new double[]{0.25, 5}),
            Map.entry("display.bodyScale", new double[]{0.25, 5}), Map.entry("api.glowLingerSeconds", new double[]{0, 600}),
            Map.entry("canvasKeys.degreesPerSecond", new double[]{1, 720}), Map.entry("identify.tolerancePixels", new double[]{0, 200}),
            Map.entry("identify.tooltipDwellMillis", new double[]{0, 10_000}), Map.entry("identify.stars.maxMagnitude", new double[]{-2, 30}));

    /**
     * Why {@code value} is not acceptable for {@code key}, or null. A key is held to the type of the
     * value it has now (a switch stays true or false, a number stays a number), to its range when
     * known, and to the shape of a keystroke for the keybindings.
     */
    static String check(String key, String value, String now) {
        if (value.length() > MAX_VALUE)
            return "the value is longer than " + MAX_VALUE + " characters";
        if (key.startsWith(KeyBindings.PREFIX)) {
            String v = value.strip();
            return v.isEmpty() || v.equals("none") || keystroke(v) ? null : "'" + value + "' is not a keystroke";
        }
        if (key.startsWith("canvasKeys.") && !key.equals("canvasKeys.invert") && !key.equals("canvasKeys.degreesPerSecond")) {
            String v = value.strip();
            return v.isEmpty() || v.equals("none") || KeyBindings.keyCode(v) != 0 ? null : "'" + value + "' is not a key name";
        }
        double[] r = RANGE.get(key);
        if (r != null) {
            if (!isNumber(value))
                return "'" + value + "' is not a number";
            double d = Double.parseDouble(value.strip());
            if (d < r[0] || d > r[1])
                return value + " is outside " + r[0] + " to " + r[1];
            return null;
        }
        if (isBool(now) && !isBool(value))
            return "'" + value + "' is not true or false";
        if (now != null && isNumber(now) && !value.isEmpty() && !isNumber(value))
            return "'" + value + "' is not a number";
        return null;
    }

    /** Whether text is a keystroke as KeyBindings stores it ("menu" stands for the platform's shortcut key). */
    private static boolean keystroke(String text) {
        javax.swing.KeyStroke ks = javax.swing.KeyStroke.getKeyStroke(text.replaceAll("\\bmenu\\b", "ctrl"));
        return ks != null && !ks.isOnKeyRelease();
    }

    /** Write the plan's changes: the settings in one write, the storage folders through their own file. */
    public static void apply(Plan plan) throws IOException {
        Map<String, String> settings = new LinkedHashMap<>();
        for (Change c : plan.changes()) {
            if (c.key().startsWith(STORAGE_PREFIX))
                StorageFolders.choose(c.key().substring(STORAGE_PREFIX.length()), c.to());
            else
                settings.put(c.key(), c.to());
        }
        Settings.setProperties(settings);
    }

    /** Read a file for {@link #plan} against the live settings. */
    public static Plan planFile(Path file, boolean includePaths) throws IOException {
        return plan(Files.readString(file, StandardCharsets.UTF_8), Settings.own(), storageFolders(), includePaths);
    }
}
