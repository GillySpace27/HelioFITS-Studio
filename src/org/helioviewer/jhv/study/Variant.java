package org.helioviewer.jhv.study;

import java.util.Set;

import org.json.JSONObject;

/**
 * One row of a study: a partial scene. Everything except name and enabled is the override, kept
 * as the raw JSON so keys this version does not know (a newer pizza mode, say) survive a resave.
 */
public final class Variant {

    private static final Set<String> META = Set.of("name", "enabled");

    private String name;
    private boolean enabled;
    private final JSONObject override;

    public Variant(String name, boolean enabled, JSONObject override) {
        this.name = name;
        this.enabled = enabled;
        this.override = new JSONObject(override.toString());
    }

    static Variant fromJson(JSONObject jo) {
        JSONObject o = new JSONObject();
        for (String k : jo.keySet())
            if (!META.contains(k))
                o.put(k, jo.get(k));
        return new Variant(jo.optString("name", ""), jo.optBoolean("enabled", true), o);
    }

    JSONObject toJson() {
        JSONObject jo = new JSONObject(override.toString());
        jo.put("name", name);
        if (!enabled) // absent means enabled, which keeps the file small
            jo.put("enabled", false);
        return jo;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** A copy, so callers cannot edit the stored override behind the row's back. */
    public JSONObject override() {
        return new JSONObject(override.toString());
    }

    /** The full view for this row: the scene's view with every key the row overrides replaced. */
    public JSONObject resolve(JSONObject sceneView) {
        JSONObject out = new JSONObject(sceneView.toString());
        JSONObject override = override(); // a fresh tree, so the result shares nothing with the row
        for (String k : override.keySet()) {
            if (k.equals("camera") && override.optJSONObject(k) != null) {
                JSONObject cam = out.optJSONObject("camera");
                if (cam == null)
                    cam = new JSONObject();
                JSONObject oc = override.getJSONObject(k);
                for (String ck : oc.keySet()) // a partial camera replaces only the keys it names
                    cam.put(ck, oc.get(ck));
                out.put("camera", cam);
            } else
                out.put(k, override.get(k));
        }
        return out;
    }
}
