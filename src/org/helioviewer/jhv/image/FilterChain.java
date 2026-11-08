package org.helioviewer.jhv.image;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The filters applied after a layer's first one, in series, each with a strength.
 *
 * <p>The layer's own filter (ImageFilter.Type in ImageProcessingSettings) is stage 1 and always runs
 * at full strength on the stretched frame. Each stage in this chain is stage 2 onward: it sees the
 * previous stage's output y and produces {@code (1 - w) * y + w * F(y)}, so w = 0 passes y through
 * and w = 1 is the plain nested call. "MGN, 60% on top of RHEF" is
 * {@code 0.4 * RHEF(x) + 0.6 * MGN(RHEF(x))}.
 *
 * <p>An immutable value, so it can sit in decode and cache keys. The empty chain is the single
 * filter every layer had before chains existed.
 */
public record FilterChain(List<Stage> stages) {

    /** One link: a filter and how far the output moves toward it. */
    public record Stage(ImageFilter.Type type, float strength) {
        public Stage {
            if (type == null || type == ImageFilter.Type.None)
                throw new IllegalArgumentException("a chain stage needs a filter");
            if (!(strength >= 0 && strength <= 1)) // also false for NaN
                throw new IllegalArgumentException("strength " + strength + " is outside 0 to 1");
        }
    }

    public static final FilterChain EMPTY = new FilterChain(List.of());

    /** Most stages in all, the layer's own filter included. Settable between 2 and 4; the model stores a list. */
    public static final int DEFAULT_MAX_STAGES = 3;
    public static final String MAX_STAGES_PROPERTY = "jhv.filterChain.maxStages";

    public FilterChain {
        stages = List.copyOf(stages);
    }

    public static int maxStages() {
        int n = Integer.getInteger(MAX_STAGES_PROPERTY, DEFAULT_MAX_STAGES);
        return Math.clamp(n, 2, 4);
    }

    /** Stages after the first that fit under the cap. */
    public static int maxExtraStages() {
        return maxStages() - 1;
    }

    public boolean isEmpty() {
        return stages.isEmpty();
    }

    public int size() {
        return stages.size();
    }

    public FilterChain with(int index, Stage stage) {
        List<Stage> s = new ArrayList<>(stages);
        s.set(index, stage);
        return new FilterChain(s);
    }

    public FilterChain add(Stage stage) {
        if (stages.size() >= maxExtraStages())
            throw new IllegalArgumentException("a chain holds at most " + maxStages() + " filters");
        List<Stage> s = new ArrayList<>(stages);
        s.add(stage);
        return new FilterChain(s);
    }

    public FilterChain remove(int index) {
        List<Stage> s = new ArrayList<>(stages);
        s.remove(index);
        return new FilterChain(s);
    }

    /** Whether the chain, with this first filter, runs RHEF at any strength above zero. */
    public boolean usesRhef(ImageFilter.Type first) {
        return rankFraction(first) > 0;
    }

    /**
     * How much of the output is RHEF rank, 0 to 1: m = 0 before any RHEF; an RHEF stage with strength w
     * makes m = (1 - w) * m + w; every other stage keeps m. The shader turns the instrument response
     * factor off and the Υ curve on in proportion to this, so one RHEF at full strength (m = 1) and no
     * RHEF (m = 0) reproduce the single-filter display exactly and everything between is continuous.
     * A blend of the display constants is a design choice, not something the filters dictate.
     */
    public double rankFraction(ImageFilter.Type first) {
        double m = first == ImageFilter.Type.RHEF ? 1 : 0;
        for (Stage s : stages)
            if (s.type() == ImageFilter.Type.RHEF)
                m = (1 - s.strength()) * m + s.strength();
        return m;
    }

    /** The first filter with this chain, as a sentence: "40% RHEF + 60% MGN applied to the RHEF output". */
    public static String describe(ImageFilter.Type first, FilterChain chain) {
        if (first == ImageFilter.Type.None)
            return "No filter";
        StringBuilder sb = new StringBuilder(first.name());
        for (Stage s : chain.stages()) {
            int pct = Math.round(s.strength() * 100);
            sb.append(" then ").append(pct).append("% ").append(s.type().name());
            if (pct < 100)
                sb.append(" (").append(100 - pct).append("% previous)");
        }
        return sb.toString();
    }

    public JSONArray toJson() {
        JSONArray a = new JSONArray();
        for (Stage s : stages)
            a.put(new JSONObject().put("type", s.type().name()).put("strength", exact(s.strength())));
        return a;
    }

    /** Lenient, like a session read: an unknown filter or a bad strength skips that stage, and the cap holds. */
    public static FilterChain fromJson(JSONArray a) {
        if (a == null)
            return EMPTY;
        List<Stage> out = new ArrayList<>();
        for (int i = 0; i < a.length() && out.size() < maxExtraStages(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null)
                continue;
            ImageFilter.Type type = parseType(o.optString("type", o.optString("filter", "")));
            double w = o.optDouble("strength", 1);
            if (type == null || type == ImageFilter.Type.None || !(w >= 0 && w <= 1))
                continue;
            out.add(new Stage(type, (float) w));
        }
        return new FilterChain(out);
    }

    public static ImageFilter.Type parseType(String name) {
        for (ImageFilter.Type t : ImageFilter.Type.values())
            if (t.name().equalsIgnoreCase(name == null ? "" : name.trim()))
                return t;
        return null;
    }

    /** The float as the number a person typed (0.6, not 0.6000000238418579), for JSON that people and other tools read. */
    public static double exact(float strength) {
        return Double.parseDouble(Float.toString(strength));
    }
}
