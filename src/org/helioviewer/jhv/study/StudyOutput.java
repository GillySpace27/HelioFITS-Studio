package org.helioviewer.jhv.study;

import org.json.JSONObject;

/** The "output" block of a job file. Raw-backed: unknown keys round-trip. */
public final class StudyOutput {

    public static final String DEFAULT_FOLDER = "~/Exports/{scene}_{date}";
    public static final String DEFAULT_NAME = "{time}_{variant}";

    private final JSONObject raw;

    public StudyOutput() {
        raw = new JSONObject();
    }

    StudyOutput(JSONObject raw) {
        this.raw = new JSONObject(raw.toString());
    }

    JSONObject toJson() {
        return new JSONObject(raw.toString());
    }

    public String folder() {
        return raw.optString("folder", DEFAULT_FOLDER);
    }

    public void setFolder(String s) {
        raw.put("folder", s);
    }

    public String name() {
        return raw.optString("name", DEFAULT_NAME);
    }

    public void setName(String s) {
        raw.put("name", s);
    }

    /** Stills block, e.g. {"size": [2048, 2048]}; null when stills are off. */
    public JSONObject stills() {
        JSONObject jo = raw.optJSONObject("stills");
        return jo == null ? null : new JSONObject(jo.toString());
    }

    public void setStills(JSONObject jo) {
        put("stills", jo);
    }

    /** Movies block (format, range, longSide, ...), pinned by WP6; null when movies are off. */
    public JSONObject movies() {
        JSONObject jo = raw.optJSONObject("movies");
        return jo == null ? null : new JSONObject(jo.toString());
    }

    public void setMovies(JSONObject jo) {
        put("movies", jo);
    }

    public String contactSheet() {
        return raw.optString("contactSheet", "grid");
    }

    public void setContactSheet(String s) {
        raw.put("contactSheet", s);
    }

    public String record() {
        return raw.optString("record", "sidecar+manifest");
    }

    public void setRecord(String s) {
        raw.put("record", s);
    }

    public Overwrite overwrite() {
        return Overwrite.parse(raw.optString("overwrite", null));
    }

    public void setOverwrite(Overwrite o) {
        raw.put("overwrite", o.json);
    }

    private void put(String k, JSONObject jo) {
        if (jo == null)
            raw.remove(k);
        else
            raw.put(k, new JSONObject(jo.toString()));
    }
}
