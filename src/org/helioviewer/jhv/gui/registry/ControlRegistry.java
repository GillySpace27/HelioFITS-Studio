package org.helioviewer.jhv.gui.registry;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.swing.Action;
import javax.swing.KeyStroke;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.gui.ActionCatalog;
import org.helioviewer.jhv.gui.Tooltips;
import org.helioviewer.jhv.gui.search.Tour;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.io.JSONUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Everything Studio knows about one command or control, in one place: its id, label, where it
 * lives, its tooltip, its shortcut, a link to its documentation, the guided tours and tips that
 * mention it, and (for a command) its {@link CommandSpec}. Help > Search Controls (Cmd-K), the
 * tours, the Tip of the Day and any assistant read this instead of each keeping a list of their own.
 *
 * <p>Commands come from {@link CommandRegistry}; controls (named components that are not commands)
 * come from resources/data/controls.json. Tooltips come from {@link Tooltips}, tours from
 * resources/data/tours, tips from resources/data/tips.json: this class derives, it does not copy.
 */
public final class ControlRegistry {

    public enum Kind { COMMAND, CONTROL }

    /**
     * {@code words} are extra match words (a command's description). {@code reveal} is a catalog id
     * run before a control is spotlit. {@code doc} is null when there is none.
     */
    public record Entry(Kind kind, String id, String title, String where, String words, @Nullable String reveal,
                        String tooltip, @Nullable String doc, List<String> tours, List<String> tips,
                        @Nullable CommandSpec command) {

        /**
         * The shortcut now in force as text, "" when there is none. Read from the Action each time, never kept
         * with the entry: the person can change it while the program runs (Settings > Keybindings).
         */
        public String shortcut() {
            ActionCatalog.Entry a = kind == Kind.COMMAND ? ActionCatalog.get(id) : null;
            return a != null && a.action().getValue(Action.ACCELERATOR_KEY) instanceof KeyStroke ks ? Shortcuts.text(ks) : "";
        }
    }

    private static final Map<String, Entry> entries = new LinkedHashMap<>(); // the map is the only state (guards.py ratchet)

    public static synchronized List<Entry> all() {
        build();
        return List.copyOf(entries.values());
    }

    @Nullable
    public static synchronized Entry get(String id) {
        build();
        return entries.get(id);
    }

    private static void build() {
        if (!entries.isEmpty())
            return;
        Map<String, List<String>> tours = tourTargets();
        Map<String, List<String>> tips = tipTargets();
        for (CommandSpec c : CommandRegistry.all()) {
            ActionCatalog.Entry a = ActionCatalog.get(c.id());
            String title = a == null ? c.id() : plain(String.valueOf(a.action().getValue(Action.NAME)));
            String where = a == null ? "" : a.menuPath();
            String tip = Tooltips.lookup(c.id());
            entries.put(c.id(), new Entry(Kind.COMMAND, c.id(), title, where, c.description(), null,
                    tip == null ? c.description() : tip, null, tours.getOrDefault(c.id(), List.of()),
                    tips.getOrDefault(c.id(), List.of()), c));
        }
        try (InputStream is = FileUtils.getResource("/data/controls.json")) {
            JSONArray list = JSONUtils.get(is).getJSONArray("controls");
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                String id = o.getString("id");
                if (entries.containsKey(id))
                    throw new IllegalStateException("control id " + id + " is also a command");
                String tip = Tooltips.lookup(id);
                entries.put(id, new Entry(Kind.CONTROL, id, o.getString("title"), o.getString("where"), o.optString("words"),
                        o.has("reveal") ? o.getString("reveal") : null, tip == null ? "" : tip,
                        o.has("doc") ? o.getString("doc") : null, tours.getOrDefault(id, List.of()),
                        tips.getOrDefault(id, List.of()), null));
            }
        } catch (IOException | RuntimeException e) { // RegistryCheck keeps the file readable
            Log.warn("controls.json could not be read", e);
        }
    }

    private static String plain(String label) {
        String s = label.strip();
        if (s.endsWith("..."))
            s = s.substring(0, s.length() - 3);
        else if (s.endsWith("…"))
            s = s.substring(0, s.length() - 1);
        return s.strip();
    }

    /** Control or command id to the tours (by id) that point at it or run it. */
    private static Map<String, List<String>> tourTargets() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String tour : Tour.IDS)
            for (String id : Tour.references(tour))
                add(out, id, tour);
        return out;
    }

    /** Control or command id to the tips (by id) that point at it or name it. */
    private static Map<String, List<String>> tipTargets() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (InputStream is = FileUtils.getResource("/data/tips.json")) {
            JSONArray tips = JSONUtils.get(is).getJSONArray("tips");
            for (int i = 0; i < tips.length(); i++) {
                JSONObject t = tips.getJSONObject(i);
                for (String key : new String[] { "target", "action", "reveal" })
                    if (t.has(key))
                        add(out, t.getString(key), t.getString("id"));
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("tips.json could not be read", e);
        }
        return out;
    }

    private static void add(Map<String, List<String>> map, String id, String by) {
        List<String> l = map.computeIfAbsent(id, k -> new ArrayList<>());
        if (!l.contains(by))
            l.add(by);
    }

    private ControlRegistry() {}
}
