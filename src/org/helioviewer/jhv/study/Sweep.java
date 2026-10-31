package org.helioviewer.jhv.study;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.helioviewer.jhv.app.Settings;
import org.json.JSONObject;

/**
 * A sweep: one thing a study varies besides its rows. Kept raw-backed so a job file round-trips
 * with every key it came with; {@link TimeSweep} and {@link FieldSweep} add typed readers.
 */
public class Sweep {

    public static final String TIME = "time", FIELD = "field";

    /** Settings key and default for the most files a run makes without asking. */
    public static final String RUN_LIMIT_KEY = "study.runLimit";
    public static final int DEFAULT_RUN_LIMIT = 500;

    /** ESTIMATE, not measured: bytes per pixel of a typical solar PNG, used only for the panel's size hint. */
    static final double EST_BYTES_PER_PIXEL = 1.5;
    static final int DEFAULT_W = 1600, DEFAULT_H = 1000; // size assumed for a row with no "size"

    protected final JSONObject raw;

    Sweep(JSONObject raw) {
        this.raw = new JSONObject(raw.toString());
    }

    public String kind() {
        return raw.optString("kind", "");
    }

    /** The name-pattern field a sweep varies: "time" for a time sweep, the pattern field of a field sweep. */
    public String sweptField() {
        return TIME;
    }

    /** How many values this sweep takes. */
    int size() {
        return 1;
    }

    static Sweep fromJson(JSONObject jo) {
        if (jo == null)
            return null;
        return switch (jo.optString("kind", "")) {
            case TIME -> new TimeSweep(jo);
            case FIELD -> new FieldSweep(jo);
            default -> new Sweep(jo);
        };
    }

    JSONObject toJson() {
        return new JSONObject(raw.toString());
    }

    // ---- the plan ----

    /** The steps of a study, 1-based and numbered across all of them. */
    static List<StudyRunner.Step> steps(Study study) {
        List<Variant> vs = study.enabledVariants();
        Sweep sw = study.sweep();
        if (sw instanceof TimeSweep && study.output().movies() != null)
            throw new IllegalArgumentException("A time sweep and movie output cannot be combined: pick one.");
        List<StudyRunner.Step> l = new ArrayList<>();
        if (sw instanceof TimeSweep ts) { // time outer, variant inner: one seek per time
            for (long t : ts.times())
                for (Variant v : vs)
                    l.add(new StudyRunner.Step(v, l.size() + 1, t, Map.of()));
        } else if (sw instanceof FieldSweep fs) {
            for (Variant v : vs) {
                if (fs.inherits(v)) {
                    for (double x : fs.values())
                        l.add(new StudyRunner.Step(fs.inject(v, x), l.size() + 1, null, Map.of(fs.sweptField(), FieldSweep.format(x))));
                } else
                    l.add(new StudyRunner.Step(v, l.size() + 1, null, Map.of())); // overrides the field: once, unchanged
            }
        } else
            for (Variant v : vs)
                l.add(new StudyRunner.Step(v, l.size() + 1, null, Map.of()));
        return l;
    }

    /** How many PNGs the study makes, for the count line. */
    public static int count(Study study) {
        int rows = study.enabledVariants().size();
        Sweep sw = study.sweep();
        if (sw instanceof TimeSweep ts)
            return rows * ts.size();
        if (sw instanceof FieldSweep fs) {
            int inheriting = 0;
            for (Variant v : study.enabledVariants())
                if (fs.inherits(v))
                    inheriting++;
            return inheriting * fs.size() + (rows - inheriting);
        }
        return rows;
    }

    /** ESTIMATED bytes of the PNGs (pixels x {@link #EST_BYTES_PER_PIXEL}); a rough hint, not a measurement. */
    public static long estimateBytes(Study study) {
        long total = 0;
        for (StudyRunner.Step st : steps(study)) {
            org.json.JSONArray sz = st.variant().override().optJSONArray("size");
            long w = sz != null && sz.length() == 2 ? sz.optLong(0, DEFAULT_W) : DEFAULT_W;
            long h = sz != null && sz.length() == 2 ? sz.optLong(1, DEFAULT_H) : DEFAULT_H;
            total += (long) (w * h * EST_BYTES_PER_PIXEL);
        }
        return total;
    }

    /** The run limit from Settings, or the default when unset or not a positive number. */
    public static int runLimit() {
        try {
            int n = Integer.parseInt(String.valueOf(Settings.getProperty(RUN_LIMIT_KEY)).trim());
            return n > 0 ? n : DEFAULT_RUN_LIMIT;
        } catch (NumberFormatException e) {
            return DEFAULT_RUN_LIMIT;
        }
    }

    /** Refuses a run of more than {@code limit} files; the caller asks the user and passes confirmed runs on. */
    public static void checkLimit(int count, int limit) {
        if (count > limit)
            throw new IllegalArgumentException("This run would make " + count + " files, above the limit of " + limit
                    + " (Settings key " + RUN_LIMIT_KEY + "). Confirm to run it anyway.");
    }
}
