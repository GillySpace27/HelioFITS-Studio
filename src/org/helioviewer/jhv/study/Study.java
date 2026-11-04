package org.helioviewer.jhv.study;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

/** A parameter study: one scene, a list of variants, an optional sweep, and how to write it all out. */
public final class Study {

    public static final int VERSION = 1;

    /** The study of the open session, null when there is none. Saved with the session by State. */
    public static final AtomicReference<Study> CURRENT = new AtomicReference<>();

    private final JSONObject extra; // top-level keys this version does not know
    private String scene = "";
    private String sceneSha256; // hash of the .jhv bytes at the time the job was made, may be null
    public final List<Variant> variants = new ArrayList<>();
    private Sweep sweep;
    private StudyOutput output = new StudyOutput();

    public Study() {
        extra = new JSONObject();
    }

    private Study(JSONObject extra) {
        this.extra = extra;
    }

    /** Parses the job file's object. Throws on a missing or newer version. */
    public static Study fromJson(JSONObject jo) {
        int v = jo.optInt("hfstudy", 0);
        if (v < 1)
            throw new IllegalArgumentException("Not a HelioFITS Studio study file (no \"hfstudy\" version).");
        if (v > VERSION)
            throw new IllegalArgumentException("This study file is version " + v + "; this HelioFITS Studio reads up to version "
                    + VERSION + ". Update HelioFITS Studio to open it.");
        JSONObject extra = new JSONObject(jo.toString());
        for (String k : new String[]{"hfstudy", "scene", "sceneSha256", "variants", "sweep", "output"})
            extra.remove(k);
        Study s = new Study(extra);
        s.scene = jo.optString("scene", "");
        s.sceneSha256 = jo.optString("sceneSha256", null);
        JSONArray va = jo.optJSONArray("variants");
        if (va != null)
            for (int i = 0; i < va.length(); i++)
                s.variants.add(Variant.fromJson(va.getJSONObject(i)));
        s.sweep = Sweep.fromJson(jo.optJSONObject("sweep"));
        JSONObject out = jo.optJSONObject("output");
        s.output = out == null ? new StudyOutput() : new StudyOutput(out);
        return s;
    }

    public JSONObject toJson() {
        JSONObject jo = new JSONObject(extra.toString());
        jo.put("hfstudy", VERSION);
        jo.put("scene", scene);
        if (sceneSha256 != null)
            jo.put("sceneSha256", sceneSha256);
        JSONArray va = new JSONArray();
        for (Variant v : variants)
            va.put(v.toJson());
        jo.put("variants", va);
        jo.put("sweep", sweep == null ? JSONObject.NULL : sweep.toJson());
        jo.put("output", output.toJson());
        return jo;
    }

    /** Adds the "study" key to a session object, and only when a study exists, so older files are unchanged. */
    public static void addTo(JSONObject main) {
        Study s = CURRENT.get();
        if (s != null)
            main.put("study", s.toJson());
    }

    /** Reads the "study" key of a session object back into CURRENT; a session without one clears it. */
    public static void readFrom(JSONObject main) {
        JSONObject jo = main.optJSONObject("study");
        Study s = null;
        if (jo != null) {
            try {
                s = fromJson(jo);
            } catch (IllegalArgumentException e) { // a newer study in the session: keep the session loadable
                org.helioviewer.jhv.app.Log.error("study", e);
            }
        }
        CURRENT.set(s);
    }

    public String scene() {
        return scene;
    }

    public String sceneSha256() {
        return sceneSha256;
    }

    public void setScene(String path, String sha256) {
        scene = path;
        sceneSha256 = sha256;
    }

    public Sweep sweep() {
        return sweep;
    }

    public void setSweep(Sweep s) {
        sweep = s;
    }

    public StudyOutput output() {
        return output;
    }

    public void setOutput(StudyOutput o) {
        output = o;
    }

    public List<Variant> enabledVariants() {
        List<Variant> l = new ArrayList<>();
        for (Variant v : variants)
            if (v.enabled())
                l.add(v);
        return l;
    }
}
