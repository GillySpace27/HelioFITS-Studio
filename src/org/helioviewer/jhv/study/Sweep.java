package org.helioviewer.jhv.study;

import org.json.JSONObject;

/** Stub: only the kind and the raw block are kept so a job file with a sweep round-trips. WP7 fills it. */
public class Sweep {

    public static final String TIME = "time", FIELD = "field";

    protected final JSONObject raw;

    Sweep(JSONObject raw) {
        this.raw = new JSONObject(raw.toString());
    }

    public String kind() {
        return raw.optString("kind", "");
    }

    /** The name-pattern field a sweep varies: "time" for a time sweep, the column for a field sweep. */
    public String sweptField() {
        return kind().equals(FIELD) ? raw.optString("field", "") : TIME;
    }

    static Sweep fromJson(JSONObject jo) {
        return jo == null ? null : new Sweep(jo);
    }

    JSONObject toJson() {
        return new JSONObject(raw.toString());
    }
}
