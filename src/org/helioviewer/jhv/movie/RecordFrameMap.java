package org.helioviewer.jhv.movie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.helioviewer.jhv.layers.Layers;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Which frame of each image layer was on screen in each output frame of a movie or series, taken
 * as the frames are captured (the record's frame_map, columnar). The frame number is the layer's
 * own; ProcessingRecord turns it into an index into the layer's sources, and only for a layer that
 * has one source file per frame.
 */
final class RecordFrameMap {

    private final List<String> times = new ArrayList<>();
    private final Map<String, List<Integer>> byLayer = new LinkedHashMap<>();

    /** One captured output frame at {@code time}. Call where the frame is rendered. Never throws. */
    synchronized void add(String time) {
        try {
            Map<String, Integer> now = new LinkedHashMap<>();
            Layers.forEachImageLayer(layer -> now.put(layer.getId(), layer.isEnabled() ? layer.getView().getCurrentFrameNumber() : null));
            int n = times.size();
            for (Map.Entry<String, Integer> e : now.entrySet()) {
                List<Integer> column = byLayer.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                while (column.size() < n) // a layer added mid-recording showed nothing before
                    column.add(null);
                column.add(e.getValue());
            }
            for (List<Integer> column : byLayer.values())
                while (column.size() < n + 1) // a layer removed mid-recording shows nothing from now on
                    column.add(null);
            times.add(time);
        } catch (RuntimeException e) {
            org.helioviewer.jhv.app.Log.warn("Frame map: a frame was not mapped", e);
            // the frame stays out; ProcessingRecord compares the count with the output's and drops a short map
        }
    }

    /** {"times": [...], "by_layer": {id: [frame number or null, ...]}}, or null when no frame was mapped. */
    @Nullable
    synchronized JSONObject toJson() {
        if (times.isEmpty())
            return null;
        JSONObject by = new JSONObject();
        for (Map.Entry<String, List<Integer>> e : byLayer.entrySet()) {
            JSONArray column = new JSONArray();
            for (Integer f : e.getValue())
                column.put(f == null ? JSONObject.NULL : f);
            by.put(e.getKey(), column);
        }
        return new JSONObject().put("times", new JSONArray(times)).put("by_layer", by);
    }
}
