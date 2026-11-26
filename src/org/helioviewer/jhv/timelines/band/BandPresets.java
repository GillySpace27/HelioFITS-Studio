package org.helioviewer.jhv.timelines.band;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.JSONUtils;
import org.helioviewer.jhv.timelines.TimelineLayer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The timeline presets the user saved: a name and the rows of the stack at that moment, each as the row
 * saves itself in a session (its type, colour, colour levels, axis). Kept in one file,
 * {@code ~/HFStudio/Timelines/presets.json}; the catalogue's own presets stay where they are and are never
 * written here.
 */
public final class BandPresets {

    private BandPresets() {}

    static Path file(Path dir) {
        return dir.resolve("presets.json");
    }

    public static Path file() {
        return file(Directories.TIMELINES.getFile().toPath());
    }

    /** The saved presets in the order they were saved; none when there is no file or it is damaged. */
    public static Map<String, JSONArray> load() {
        return load(file());
    }

    static Map<String, JSONArray> load(Path file) {
        Map<String, JSONArray> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file))
            return out;
        try (InputStream in = Files.newInputStream(file)) {
            JSONObject jo = JSONUtils.get(in);
            for (String name : jo.keySet()) {
                JSONArray rows = jo.optJSONArray(name);
                if (rows != null)
                    out.put(name, rows);
            }
        } catch (IOException | JSONException e) {
            Log.warn("Timeline presets could not be read, ignored: " + file, e);
        }
        return out;
    }

    public static boolean save(Map<String, JSONArray> presets) {
        return save(file(), presets);
    }

    static boolean save(Path file, Map<String, JSONArray> presets) {
        try {
            Files.createDirectories(file.getParent());
            JSONObject jo = new JSONObject();
            presets.forEach(jo::put);
            Path tmp = file.resolveSibling("presets.json.tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                out.write(jo.toString(1).getBytes(StandardCharsets.UTF_8));
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            Log.warn("Timeline presets could not be saved: " + file, e);
            return false;
        }
    }

    /** A deleted preset is kept, not lost: appended to presets-retired.json beside the file (nothing the user made is erased). */
    public static void retire(String name, JSONArray rows) {
        retire(file(), name, rows);
    }

    static void retire(Path presets, String name, JSONArray rows) {
        Path file = presets.resolveSibling("presets-retired.json");
        Map<String, JSONArray> kept = load(file);
        String key = name;
        for (int i = 2; kept.containsKey(key); i++)
            key = name + " (" + i + ")";
        kept.put(key, rows);
        save(file, kept);
    }

    /** The rows among {@code layers}, each as it saves itself in a session. */
    public static JSONArray capture(List<? extends TimelineLayer> layers) {
        JSONArray ja = new JSONArray();
        for (TimelineLayer layer : layers)
            if (layer instanceof Band band) {
                JSONObject jo = new JSONObject();
                band.serialize(jo);
                ja.put(jo);
            }
        return ja;
    }

    /** The rows of a saved preset; a damaged one is left out and the rest come back. */
    public static List<Band> restore(JSONArray rows) {
        List<Band> bands = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject jo = rows.optJSONObject(i);
            if (jo == null)
                continue;
            try {
                bands.add((Band) Band.deserialize(jo));
            } catch (JSONException e) {
                Log.warn("A row of a timeline preset could not be restored, left out", e);
            }
        }
        return bands;
    }

}
