package org.helioviewer.jhv.gui.dialog;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.io.JSONUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Welcome window's tip (HS-10): resources/data/tips.json, one tip per opening, in turn. A tip
 * may name the ActionCatalog id whose menu path its text spells out; WelcomeTipsCheck holds the
 * text to that path, so a renamed menu item cannot leave a tip pointing at nothing.
 */
final class WelcomeTips {

    /** The index of the tip shown last; the next opening shows the one after it. */
    static final String INDEX_KEY = "startup.welcomeTip";

    record Tip(String text, @Nullable String action) {}

    static List<Tip> parse(JSONObject jo) {
        JSONArray array = jo.getJSONArray("tips");
        List<Tip> tips = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject t = array.getJSONObject(i);
            tips.add(new Tip(t.getString("text"), t.has("action") ? t.getString("action") : null));
        }
        return tips;
    }

    static List<Tip> load() {
        try (InputStream is = FileUtils.getResource("/data/tips.json")) {
            return parse(JSONUtils.get(is));
        } catch (IOException | RuntimeException e) { // WelcomeTipsCheck keeps the file readable; never a crash here
            Log.warn("Welcome tips could not be read", e);
            return List.of();
        }
    }

    /** The index after {@code stored} among {@code size} tips; the first when nothing usable is stored. */
    static int nextIndex(@Nullable String stored, int size) {
        if (size <= 0)
            return -1;
        try {
            return stored == null ? 0 : Math.floorMod(Integer.parseInt(stored.strip()) + 1, size);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The next tip in turn, remembered as shown; null when there are none. */
    @Nullable
    static Tip next() {
        List<Tip> tips = load();
        int i = nextIndex(Settings.getProperty(INDEX_KEY), tips.size());
        if (i < 0)
            return null;
        Settings.setProperty(INDEX_KEY, Integer.toString(i));
        return tips.get(i);
    }

    private WelcomeTips() {}

}
