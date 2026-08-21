package org.helioviewer.jhv.image.lut;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.io.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Category labels for index-coded colour tables, read from /luts/lut-labels.json.
 * <p>
 * A LUT named in that file is categorical: its pixel values are category IDs, and the legend is a
 * row of discrete blocks. A LUT that is absent is continuous and gets a gradient instead, so the
 * file is purely additive -- no existing colour table changes behaviour by its presence.
 */
public final class LUTLabels {

    /** One legend block: the raw pixel values it covers, and the name shown beneath them. */
    public record Group(int[] indices, String label) {}

    private static final Map<String, List<Group>> labels = load();

    /** Legend groups for a colour table, or null if it is not categorical. */
    @Nullable
    public static List<Group> get(String lutName) {
        return labels.get(lutName);
    }

    private static Map<String, List<Group>> load() {
        Map<String, List<Group>> loaded = new HashMap<>();
        try (InputStream is = FileUtils.getResource("/luts/lut-labels.json");
             BufferedReader in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            JSONObject root = new JSONObject(new JSONTokener(in));
            for (String name : root.keySet()) {
                if (name.startsWith("_")) // prose blocks, not data
                    continue;
                List<Group> groups = readGroups(root, name);
                if (!groups.isEmpty())
                    loaded.put(name, List.copyOf(groups));
            }
        } catch (Exception e) {
            // A malformed or missing sidecar must not stop the app: every LUT simply stays
            // continuous, which is the pre-existing behaviour.
            Log.warn("Could not read LUT labels", e);
        }
        return Map.copyOf(loaded);
    }

    private static List<Group> readGroups(JSONObject root, String name) {
        List<Group> groups = new ArrayList<>();
        JSONArray arr = root.optJSONArray(name);
        if (arr == null) {
            Log.warn("Ignoring LUT labels for '" + name + "': not an array");
            return groups;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject jo = arr.optJSONObject(i);
            JSONArray idx = jo == null ? null : jo.optJSONArray("indices");
            String label = jo == null ? null : jo.optString("label", null);
            if (idx == null || idx.isEmpty() || label == null) {
                Log.warn("Ignoring malformed LUT label entry " + i + " for '" + name + "'");
                continue;
            }
            int[] indices = new int[idx.length()];
            for (int j = 0; j < indices.length; j++)
                indices[j] = idx.optInt(j);
            groups.add(new Group(indices, label));
        }
        return groups;
    }

    private LUTLabels() {}
}
