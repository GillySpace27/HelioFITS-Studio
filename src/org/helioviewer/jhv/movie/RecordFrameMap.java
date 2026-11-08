package org.helioviewer.jhv.movie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Which source frame of each image layer was on screen in each output frame of a movie or series, taken
 * as the frames are captured. A layer's frame number is its index in time order, which is not the order of its
 * source list, so each entry is the frame's own key (View.frameKey: the URI of the file for a file layer).
 * ProcessingRecord finds that key in the layer's sources to write the record's frame_map.
 */
final class RecordFrameMap {

    private final List<String> times = new ArrayList<>();
    private final Map<String, List<String>> byLayer = new LinkedHashMap<>();

    /** One captured output frame at {@code time}. Call where the frame is rendered. Never throws. */
    synchronized void add(String time) {
        try {
            Map<String, String> now = new LinkedHashMap<>();
            Layers.forEachImageLayer(layer -> {
                View view = layer.getView();
                now.put(layer.getId(), layer.isEnabled() ? view.frameKey(view.getCurrentFrameNumber()) : null);
            });
            int n = times.size();
            for (Map.Entry<String, String> e : now.entrySet()) {
                List<String> column = byLayer.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                while (column.size() < n) // a layer added mid-recording showed nothing before
                    column.add(null);
                column.add(e.getValue());
            }
            for (List<String> column : byLayer.values())
                while (column.size() < n + 1) // a layer removed mid-recording shows nothing from now on
                    column.add(null);
            times.add(time);
        } catch (RuntimeException e) {
            org.helioviewer.jhv.app.Log.warn("Frame map: a frame was not mapped", e);
            // the frame stays out; ProcessingRecord compares the count with the output's and drops a short map
        }
    }

    /** {"times": [...], "by_layer_key": {id: [frame key or null, ...]}}, or null when no frame was mapped. */
    @Nullable
    synchronized JSONObject toJson() {
        if (times.isEmpty())
            return null;
        JSONObject by = new JSONObject();
        for (Map.Entry<String, List<String>> e : byLayer.entrySet()) {
            JSONArray column = new JSONArray();
            for (String f : e.getValue())
                column.put(f == null ? JSONObject.NULL : f);
            by.put(e.getKey(), column);
        }
        return new JSONObject().put("times", new JSONArray(times)).put("by_layer_key", by);
    }
}
