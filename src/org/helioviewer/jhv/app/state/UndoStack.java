package org.helioviewer.jhv.app.state;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Scene undo and redo: a bounded history of whole-scene snapshots ({@link State#snapshot}), one per
 * settled change.
 *
 * <p>Every setting a user can change is already written by the session state, so the history is
 * snapshots rather than an edit per control. A change counts once the scene has held still for
 * {@link #SETTLE_MS}, which makes a slider drag, or a colour table picked and then a window nudged,
 * one step instead of one per tick. Pure: no timer and no Swing. SceneUndo polls the live scene into
 * {@link #observe}; UndoStackCheck drives it with hand-made snapshots and a fake clock.
 *
 * <p>Two snapshots are the same step when their {@link #key}s agree, and the key holds only what
 * {@link State#apply} restores without downloading or re-reading anything. So the playhead, a layer's
 * time range or file list, the master range and cadence, timelines, automation tracks, plugin state,
 * the camera's pan and zoom and the data overlays (PFSS, events, point clouds, models) never make a
 * step: undoing one would either do nothing or reload data, and both are worse than no step.
 */
public final class UndoStack {

    /** How many steps back. Each is a whole snapshot: kilobytes, tens of them for long file lists. */
    public static final int DEPTH = 50;
    /** How long the scene must hold still before a change is a step. */
    public static final long SETTLE_MS = 600;

    private record Step(JSONObject snapshot, String label) {}

    private final int depth;
    private final ArrayDeque<Step> undo = new ArrayDeque<>();
    private final ArrayDeque<Step> redo = new ArrayDeque<>();

    @Nullable
    private JSONObject current; // the settled scene the history ends at
    @Nullable
    private JSONObject currentKey;
    @Nullable
    private JSONObject pendingKey; // the scene last observed, not yet settled
    private long pendingSince;
    // An undo or redo just handed out a snapshot to apply. Whatever settles next is that apply's
    // result and becomes the present as it is; pushing it as an edit would clear the redo the undo
    // just made, and the result need not be byte-identical to the snapshot (a playback range the
    // player recomputes, a layer still arriving).
    private boolean adopting;

    public UndoStack(int _depth) {
        depth = _depth;
    }

    /** The live scene at time {@code now}. True when it settled into a new step. */
    public boolean observe(JSONObject snapshot, long now) {
        JSONObject key = key(snapshot);
        if (pendingKey == null || !pendingKey.similar(key)) {
            pendingKey = key;
            pendingSince = now;
            return false;
        }
        return now - pendingSince >= SETTLE_MS && record(snapshot, key);
    }

    /** Take the live scene as settled now: an Undo pressed straight after an edit undoes that edit. */
    public boolean flush(JSONObject snapshot) {
        pendingKey = null;
        return !adopting && record(snapshot, key(snapshot));
    }

    private boolean record(JSONObject snapshot, JSONObject key) {
        if (current == null || currentKey == null || adopting) {
            adopting = false;
            current = snapshot;
            currentKey = key;
            return false;
        }
        if (key.similar(currentKey)) { // the same step; keep the fresher snapshot (a layer's new file list)
            current = snapshot;
            return false;
        }
        undo.push(new Step(current, label(currentKey, key)));
        while (undo.size() > depth)
            undo.removeLast();
        redo.clear();
        current = snapshot;
        currentKey = key;
        return true;
    }

    /** The snapshot to apply to step back, or null when there is none. */
    @Nullable
    public JSONObject undo() {
        Step step = undo.poll();
        if (step == null || current == null)
            return null;
        redo.push(new Step(current, step.label()));
        return moveTo(step.snapshot());
    }

    /** The snapshot to apply to step forward again, or null when there is none. */
    @Nullable
    public JSONObject redo() {
        Step step = redo.poll();
        if (step == null || current == null)
            return null;
        undo.push(new Step(current, step.label()));
        return moveTo(step.snapshot());
    }

    private JSONObject moveTo(JSONObject snapshot) {
        current = snapshot;
        currentKey = key(snapshot);
        pendingKey = null;
        adopting = true;
        return snapshot;
    }

    /** Forget everything; the next settled scene starts a new history. */
    public void clear() {
        undo.clear();
        redo.clear();
        current = currentKey = pendingKey = null;
        adopting = false;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** What Undo would take back, such as "Levels change", or null. */
    @Nullable
    public String undoLabel() {
        Step step = undo.peek();
        return step == null ? null : step.label();
    }

    @Nullable
    public String redoLabel() {
        Step step = redo.peek();
        return step == null ? null : step.label();
    }

    public int undoDepth() {
        return undo.size();
    }

    // ---- what counts as a change, and what it is called -----------------------------------------

    static final String ROOT = "org.helioviewer.jhv.state";

    // Top-level entries State.apply restores. The mode names are ViewState.writeModeJson's.
    private static final List<String> SCENE = List.of("multiview", "projection", "surfaceModel", "warpLambda",
            "annotationMode", "tracking", "refresh", "showCorona", "differentialRotation", "helioradial3D",
            "playback", "recording", "annotations");
    private static final Set<String> PROJECTION = Set.of("projection", "surfaceModel", "warpLambda", "helioradial3D");

    // An image layer's entry, cut to what State.apply changes in place. Its source (request, files,
    // pointing, fixed range) is left out on purpose: see the class comment.
    private static final List<String> IMAGE_DATA = List.of("imageParams", "filter", "sequence");
    // Written by the loader from the file itself, not chosen by anyone.
    private static final String PLANES = "planes";

    private static final Map<String, String> PARAM_LABELS = new HashMap<>();

    static {
        for (String k : List.of("brightOffset", "brightScale"))
            PARAM_LABELS.put(k, "Levels change");
        for (String k : List.of("lut", "invert"))
            PARAM_LABELS.put(k, "Colormap change");
        for (String k : List.of("clippingMode", "clippingMin", "clippingMax", "scalingMode", "gamma", "beta", "alpha"))
            PARAM_LABELS.put(k, "FITS settings change");
        for (String k : List.of("innerMask", "outerMask"))
            PARAM_LABELS.put(k, "Mask change");
        PARAM_LABELS.put("opacity", "Opacity change");
        PARAM_LABELS.put("blend", "Blend change");
        PARAM_LABELS.put("sharpen", "Sharpen change");
        PARAM_LABELS.put("plane", "Plane change");
    }

    /** The part of a snapshot undo restores, comparable with {@link JSONObject#similar}. */
    static JSONObject key(JSONObject snapshot) {
        JSONObject key = new JSONObject();
        JSONObject main = snapshot.optJSONObject(ROOT);
        if (main == null)
            return key;
        for (String name : SCENE)
            if (main.has(name))
                key.put(name, main.get(name));

        JSONObject overlays = new JSONObject();
        JSONArray layers = main.optJSONArray("layers");
        if (layers != null)
            for (Object o : layers)
                if (o instanceof JSONObject entry && StateApply.OVERLAYS.contains(entry.optString("className")))
                    overlays.put(entry.optString("className"), overlayKey(entry));
        key.put("layers", overlays);

        JSONArray images = new JSONArray();
        JSONArray imageLayers = main.optJSONArray("imageLayers");
        if (imageLayers != null)
            for (Object o : imageLayers)
                if (o instanceof JSONObject entry)
                    images.put(imageKey(entry));
        key.put("imageLayers", images);
        return key;
    }

    /** A built-in overlay's entry without the camera's pan, zoom and rotation, which are navigation. */
    static JSONObject overlayKey(JSONObject entry) {
        JSONObject data = entry.optJSONObject("data");
        return new JSONObject().put("enabled", entry.optBoolean("enabled", false))
                .put("data", data == null ? new JSONObject() : without(data, "camera"));
    }

    static JSONObject imageKey(JSONObject entry) {
        JSONObject key = new JSONObject().put("id", entry.optString("id"))
                .put("enabled", entry.optBoolean("enabled", false)).put("master", entry.optBoolean("master", false));
        JSONObject data = entry.optJSONObject("data");
        if (data != null)
            for (String name : IMAGE_DATA)
                if (data.has(name))
                    key.put(name, name.equals("imageParams") ? without(data.getJSONObject(name), PLANES) : data.get(name));
        return key;
    }

    // A shallow copy less one entry; the snapshot itself is kept whole for the apply.
    private static JSONObject without(JSONObject jo, String name) {
        JSONObject copy = new JSONObject();
        for (String k : jo.keySet())
            if (!k.equals(name))
                copy.put(k, jo.get(k));
        return copy;
    }

    private static boolean same(@Nullable Object x, @Nullable Object y) {
        if (x instanceof JSONObject jx)
            return jx.similar(y);
        if (x instanceof JSONArray ax)
            return ax.similar(y);
        return String.valueOf(x).equals(String.valueOf(y));
    }

    /** What going from {@code before} to {@code after} did, as the menu names it: "Levels change". */
    static String label(JSONObject before, JSONObject after) {
        Set<String> what = new LinkedHashSet<>();

        Map<String, JSONObject> was = byId(before.optJSONArray("imageLayers"));
        Map<String, JSONObject> now = byId(after.optJSONArray("imageLayers"));
        long added = now.keySet().stream().filter(id -> !was.containsKey(id)).count();
        long removed = was.keySet().stream().filter(id -> !now.containsKey(id)).count();
        if (added > 0)
            what.add(added == 1 ? "Add layer" : "Add layers");
        if (removed > 0)
            what.add(removed == 1 ? "Remove layer" : "Remove layers");
        if (added == 0 && removed == 0 && !List.copyOf(was.keySet()).equals(List.copyOf(now.keySet())))
            what.add("Layer order change");
        for (Map.Entry<String, JSONObject> e : now.entrySet()) {
            JSONObject a = was.get(e.getKey());
            if (a != null)
                imageLabels(a, e.getValue(), what);
        }

        JSONObject overlaysBefore = before.optJSONObject("layers");
        JSONObject overlaysAfter = after.optJSONObject("layers");
        if (overlaysAfter != null)
            for (String className : overlaysAfter.keySet()) {
                JSONObject a = overlaysBefore == null ? null : overlaysBefore.optJSONObject(className);
                if (!same(a, overlaysAfter.get(className)))
                    what.add(overlayName(className) + " change");
            }

        for (String name : SCENE) {
            if (same(before.opt(name), after.opt(name)))
                continue;
            what.add(PROJECTION.contains(name) ? "Projection change"
                    : name.equals("annotations") ? "Annotation change"
                    : name.equals("playback") ? "Playback change"
                    : name.equals("recording") ? "Recording settings change"
                    : "View mode change");
        }
        return what.size() == 1 ? what.iterator().next() : what.isEmpty() ? "Change" : "Multiple changes";
    }

    private static void imageLabels(JSONObject a, JSONObject b, Set<String> what) {
        if (a.optBoolean("enabled") != b.optBoolean("enabled"))
            what.add("Show/Hide layer");
        if (a.optBoolean("master") != b.optBoolean("master"))
            what.add("Master layer change");
        if (!a.optString("filter").equals(b.optString("filter")))
            what.add("Filter change");
        if (!same(a.opt("sequence"), b.opt("sequence")))
            what.add("Sequence filter change");
        JSONObject pa = a.optJSONObject("imageParams");
        JSONObject pb = b.optJSONObject("imageParams");
        if (pa == null || pb == null) {
            if (pa != pb)
                what.add("Layer settings change");
            return;
        }
        Set<String> names = new LinkedHashSet<>(pa.keySet());
        names.addAll(pb.keySet());
        for (String name : names) {
            if (!same(pa.opt(name), pb.opt(name)))
                what.add(PARAM_LABELS.getOrDefault(name, "Layer settings change"));
        }
    }

    private static String overlayName(String className) {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return simple.endsWith("Layer") ? simple.substring(0, simple.length() - "Layer".length()) : simple;
    }

    private static Map<String, JSONObject> byId(@Nullable JSONArray entries) {
        Map<String, JSONObject> map = new LinkedHashMap<>();
        if (entries != null)
            for (Object o : entries)
                if (o instanceof JSONObject jo)
                    map.put(jo.optString("id"), jo);
        return map;
    }
}
