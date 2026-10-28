package org.helioviewer.jhv.app.state;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.ToLongBiFunction;

import javax.annotation.Nullable;

import org.helioviewer.jhv.time.TimeUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The part of opening a template that works on the session JSON alone: read its "template"
 * object, and move every restorable layer request and the master range to the range the template's
 * time rule resolves to, before any layer is built from it (State.load). Pure: TemplateRoundTripCheck
 * runs it on JSON with no window.
 *
 * <p>What moves: each image layer's "APIRequest" and "fitsRequest" start and end, the master range
 * and the playhead (to the end, the newest frame). A FITS layer's cached file list ("uris") is
 * dropped from the in-memory copy when it has a query beside it, because the list names the files
 * of the saved dates and the layer restores from the list when one is there; without it the layer
 * re-runs its query over the new range. A layer with only a file list (a local file, a Heliad
 * dataset opened from the library) has no query to move and keeps its files.
 */
public final class TemplateState {

    /** The key of the template object inside "org.helioviewer.jhv.state". */
    public static final String KEY = "template";

    /** The template this state carries, or null for a plain session. Throws when the object is malformed. */
    @Nullable
    public static TemplateTime.Meta meta(JSONObject state) {
        JSONObject jo = state.optJSONObject(KEY);
        return jo == null ? null : TemplateTime.Meta.fromJson(jo);
    }

    /**
     * The newest time every catalogued layer of the scene has data at: the oldest of the newest
     * times {@code lookup} knows for the layers' Helioviewer sources (server, sourceId), or
     * {@link Long#MIN_VALUE} when it knows none. The oldest rather than the newest, so a scene
     * that mixes a prompt archive with a slow one opens on a range where both have frames.
     * {@code lookup} answers {@link Long#MIN_VALUE} for a source it does not know.
     */
    public static long newest(JSONObject state, ToLongBiFunction<String, Integer> lookup) {
        long newest = Long.MAX_VALUE;
        JSONArray layers = state.optJSONArray("imageLayers");
        if (layers != null)
            for (Object o : layers)
                if (o instanceof JSONObject jo && jo.optJSONObject("data") instanceof JSONObject data
                        && data.optJSONObject("APIRequest") instanceof JSONObject req) {
                    long t = lookup.applyAsLong(req.optString("server", ""), req.optInt("sourceId", -1));
                    if (t != Long.MIN_VALUE)
                        newest = Math.min(newest, t);
                }
        return newest == Long.MAX_VALUE ? Long.MIN_VALUE : newest;
    }

    /** {@code session} (a whole .jhv, modified in place and returned) saved as a template described by {@code meta}. */
    public static JSONObject attach(JSONObject session, TemplateTime.Meta meta) {
        session.getJSONObject("org.helioviewer.jhv.state").put(KEY, meta.toJson());
        return session;
    }

    /**
     * Opening a template: read its object from {@code state} (the inside of a .jhv) and, for a
     * latest or relative one, move the scene to the range it resolves to at {@code now}. Returns
     * the template, or null for a plain session, which is left as it is. A fixed template is left
     * as it is too. Throws, leaving {@code state} untouched, when the template object is malformed.
     */
    @Nullable
    public static TemplateTime.Meta prepare(JSONObject state, long now, ToLongBiFunction<String, Integer> lookup) {
        TemplateTime.Meta meta = meta(state);
        if (meta == null)
            return null;
        TemplateTime.Range range = TemplateTime.resolve(meta, now, meta.mode() == TemplateTime.Mode.LATEST ? newest(state, lookup) : Long.MIN_VALUE);
        if (range != null)
            rewrite(state, range);
        return meta;
    }

    /** Move the scene in {@code state} (modified in place) to {@code range}. */
    public static void rewrite(JSONObject state, TemplateTime.Range range) {
        String start = TimeUtils.format(range.start()), end = TimeUtils.format(range.end());
        state.put("masterStartTime", start);
        state.put("masterEndTime", end);
        state.put("time", end);
        JSONArray layers = state.optJSONArray("imageLayers");
        if (layers == null)
            return;
        for (Object o : layers) {
            if (!(o instanceof JSONObject jo) || !(jo.optJSONObject("data") instanceof JSONObject data))
                continue;
            JSONObject api = data.optJSONObject("APIRequest");
            if (api != null)
                api.put("startTime", start).put("endTime", end);
            JSONObject fits = data.optJSONObject("fitsRequest");
            if (fits != null) {
                fits.put("startTime", start).put("endTime", end);
                data.remove("uris"); // the saved dates' files; the query finds the new range's
            }
        }
    }

    /**
     * The scene's datasets in a few words, for the template manager: the image layers' names in
     * order, each once ("AIA 171, AIA 193, LASCO C2"), or "No image layers".
     */
    public static String datasets(JSONObject state) {
        Set<String> names = new LinkedHashSet<>();
        JSONArray layers = state.optJSONArray("imageLayers");
        if (layers != null)
            for (Object o : layers)
                if (o instanceof JSONObject jo) {
                    String name = jo.optString("name", "").strip();
                    if (!name.isEmpty())
                        names.add(name);
                }
        return names.isEmpty() ? "No image layers" : String.join(", ", names);
    }

    private TemplateState() {}
}
