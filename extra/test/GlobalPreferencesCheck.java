package org.helioviewer.jhv.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import org.helioviewer.jhv.io.Directories;

/**
 * Save and load of the global preferences: what travels, what never does, and what a load refuses.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.GlobalPreferencesCheck
 */
public final class GlobalPreferencesCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static boolean refuses(String json) {
        try {
            GlobalPreferences.plan(json, Map.of(), Map.of(), false);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static String file(String settings) {
        return "{\"format\":\"" + GlobalPreferences.FORMAT + "\",\"version\":1,\"settings\":{" + settings + "}}";
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", Files.createTempDirectory("hfs-global-prefs").toString());
        Platform.init();
        Directories.createPersistentDirs();

        // what travels
        expect("a keybinding travels", GlobalPreferences.classify("keys.saveSession", "menu S") == GlobalPreferences.Kind.SETTING);
        expect("a Canvas key travels", GlobalPreferences.classify("canvasKeys.canvasYawLeft", "J") == GlobalPreferences.Kind.SETTING);
        expect("a load guard limit travels", GlobalPreferences.classify("load.confirmMB", "1000") == GlobalPreferences.Kind.SETTING);
        for (String secret : new String[]{"proxy.password", "proxy.username", "api.enabled", "feedback.installId", "ui.windowBounds",
                "update.lastCheck", "presentation.outputScreen", "ui.section.LASCO_C2_Layer_Options", "something.new", "keys.a b"})
            expect(secret + " never travels", GlobalPreferences.classify(secret, "x") == GlobalPreferences.Kind.SKIP);
        expect("path.local is a path", GlobalPreferences.classify("path.local", "/Users/g/Downloads") == GlobalPreferences.Kind.PATH);
        expect("an unclassified key with a path value is a path", GlobalPreferences.classify("display.newThing", "/Users/g/x") == GlobalPreferences.Kind.PATH);
        expect("a Windows path value is a path", GlobalPreferences.classify("display.newThing", "C:\\Users\\g") == GlobalPreferences.Kind.PATH);

        // save
        Map<String, String> own = new TreeMap<>(Map.of("keys.saveSession", "menu shift S", "load.confirmMB", "2500", "display.uiScale", "1.25",
                "proxy.password", "hunter2", "path.local", "/Users/g/Downloads", "ui.windowBounds", "0,0,10,10"));
        String plain = GlobalPreferences.export(own, Map.of("storage.exports", "/Volumes/X"), false, "9.9.9", Instant.parse("2026-11-08T00:00:00Z"));
        expect("default export has the settings", plain.contains("\"load.confirmMB\": \"2500\"") && plain.contains("\"keys.saveSession\": \"menu shift S\""));
        expect("default export has no secret", !plain.contains("hunter2") && !plain.contains("proxy"));
        expect("default export has no path and no window bounds", !plain.contains("/Users/g") && !plain.contains("/Volumes") && !plain.contains("windowBounds"));
        expect("export says it has no paths", plain.contains("\"includesPaths\": false"));
        String full = GlobalPreferences.export(own, Map.of("storage.exports", "/Volumes/X"), true, "9.9.9", Instant.parse("2026-11-08T00:00:00Z"));
        expect("export with paths has them, still no secret", full.contains("/Users/g/Downloads") && full.contains("/Volumes/X") && !full.contains("hunter2"));
        expect("the export is valid JSON", new org.json.JSONObject(full).getInt("version") == 1);

        // load
        GlobalPreferences.Plan back = GlobalPreferences.plan(plain, Map.of("load.confirmMB", "1000"), Map.of(), false);
        expect("a round trip plans the three settings (one differs, none are ignored)", back.changes().size() == 3 && back.ignored().isEmpty() && back.rejected().isEmpty());
        expect("a plan shows the old and new value", back.summary().contains("load.confirmMB: 1000  ->  2500"));
        GlobalPreferences.Plan same = GlobalPreferences.plan(plain, Map.of("load.confirmMB", "2500", "keys.saveSession", "menu shift S", "display.uiScale", "1.25"), Map.of(), false);
        expect("a file that matches plans nothing", same.isEmpty() && same.unchanged() == 3);
        GlobalPreferences.Plan withPaths = GlobalPreferences.plan(full, Map.of(), Map.of(), true);
        expect("paths and storage folders apply when asked", withPaths.changes().stream().anyMatch(c -> c.key().equals("path.local"))
                && withPaths.changes().stream().anyMatch(c -> c.key().equals("storage.exports")));
        GlobalPreferences.Plan heldBack = GlobalPreferences.plan(full, Map.of(), Map.of(), false);
        expect("paths are held back by default", heldBack.held().size() == 2 && heldBack.changes().stream().noneMatch(c -> c.key().equals("path.local")));

        // refusals
        expect("not JSON is refused", refuses("hello"));
        expect("another JSON file is refused", refuses("{\"version\":1,\"settings\":{}}"));
        expect("a newer format is refused", refuses("{\"format\":\"" + GlobalPreferences.FORMAT + "\",\"version\":2,\"settings\":{}}"));
        expect("a file with no settings is refused", refuses("{\"format\":\"" + GlobalPreferences.FORMAT + "\",\"version\":1}"));

        // per-key checks
        GlobalPreferences.Plan odd = GlobalPreferences.plan(file("\"load.confirmMB\":\"lots\",\"display.uiScale\":\"9\",\"keys.saveSession\":\"((x\","
                + "\"canvasKeys.canvasYawLeft\":\"NOSUCHKEY\",\"identify.enabled\":\"maybe\",\"proxy.password\":\"p\",\"unknown.key\":\"1\",\"load.confirmFrames\":\"500\""), Map.of("identify.enabled", "true"), Map.of(), false);
        expect("a non-number, an out-of-range scale, a bad keystroke, a bad key name and a bad switch are each rejected", odd.rejected().size() == 5);
        expect("a secret and an unknown key are ignored, not applied", odd.ignored().containsAll(java.util.List.of("proxy.password", "unknown.key")));
        expect("the valid key still applies", odd.changes().size() == 1 && odd.changes().get(0).key().equals("load.confirmFrames"));
        expect("none, empty and a plain keystroke are accepted", GlobalPreferences.plan(file("\"keys.a\":\"none\",\"keys.b\":\"\",\"canvasKeys.canvasYawLeft\":\"J\""), Map.of(), Map.of(), false).rejected().isEmpty());

        // apply and the exit copy, against a throwaway home
        Settings.setProperty("load.confirmMB", "1000");
        GlobalPreferences.apply(GlobalPreferences.plan(plain, Settings.own(), Map.of(), false));
        expect("apply writes the settings", "2500".equals(Settings.getProperty("load.confirmMB")) && "1.25".equals(Settings.getProperty("display.uiScale")));
        expect("apply never wrote a secret", Settings.getProperty("proxy.password") == null);
        expect("a second load has nothing to do", GlobalPreferences.plan(plain, Settings.own(), Map.of(), false).isEmpty());
        Settings.setProperty("proxy.password", "hunter2");
        Settings.setProperty("path.local", "/Users/g/Downloads");
        GlobalPreferences.backupOnExit();
        Path backup = Path.of(Directories.BACKUPS.getPath(), "preferences-" + java.time.LocalDate.now() + ".json");
        expect("a quit leaves a dated copy", Files.isRegularFile(backup));
        String kept = Files.readString(backup);
        expect("the copy has the settings and the paths but no secret", kept.contains("\"load.confirmMB\": \"2500\"") && kept.contains("/Users/g/Downloads") && !kept.contains("hunter2"));
        Settings.setProperty(GlobalPreferences.BACKUP_KEY, "false");
        Files.delete(backup); // the check's own file in its own temporary home
        GlobalPreferences.backupOnExit();
        expect("the copy can be switched off", !Files.exists(backup));

        System.out.println(failures == 0 ? "GlobalPreferencesCheck: all ok" : "GlobalPreferencesCheck: " + failures + " FAILED");
        if (failures > 0)
            System.exit(1);
    }
}
