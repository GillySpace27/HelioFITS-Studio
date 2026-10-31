package org.helioviewer.jhv.study;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A sweep over one numeric column: {"field":"warpLambda"|"fov"|"zoom","values":[...]} or start/step/count.
 * A row inherits the field when its override lacks the key (fov and zoom live in override.camera).
 */
final class FieldSweep extends Sweep {

    FieldSweep(JSONObject raw) {
        super(raw);
    }

    String column() {
        return raw.optString("field", "");
    }

    /** The name-pattern field of the column: {lambda} for warpLambda, else the column itself. */
    @Override
    public String sweptField() {
        return column().equals("warpLambda") ? "lambda" : column();
    }

    private boolean inCamera() {
        return !column().equals("warpLambda");
    }

    List<Double> valueList() {
        List<Double> l = new ArrayList<>();
        JSONArray a = raw.optJSONArray("values");
        if (a != null) {
            for (int i = 0; i < a.length(); i++)
                l.add(a.getDouble(i));
        } else if (raw.has("start")) {
            double start = raw.getDouble("start"), step = raw.optDouble("step", 1);
            for (int i = 0, n = raw.optInt("count", 1); i < n; i++)
                l.add(start + i * step);
        }
        return l;
    }

    double[] values() {
        return valueList().stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Override
    int size() {
        return valueList().size();
    }

    boolean inherits(Variant v) {
        JSONObject o = v.override();
        if (!inCamera())
            return !o.has(column());
        JSONObject cam = o.optJSONObject("camera");
        return cam == null || !cam.has(column());
    }

    /** A copy of the row with the value written into its override. */
    Variant inject(Variant v, double x) {
        JSONObject o = v.override();
        if (inCamera()) {
            JSONObject cam = o.optJSONObject("camera");
            if (cam == null)
                cam = new JSONObject();
            cam.put(column(), x);
            o.put("camera", cam);
        } else
            o.put(column(), x);
        return new Variant(v.name(), v.enabled(), o);
    }

    static String format(double x) {
        return JSONObject.numberToString(x);
    }
}
