package org.helioviewer.jhv.automation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.helioviewer.jhv.time.TimeUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One animated parameter: the parameter's name, a sorted list of keys, and whether it applies.
 *
 * <p>Values are stored in the parameter's own physical units, never in slider ticks. The Crop
 * slider's position maps to a radius through the layer stack's largest radial size, so a stored
 * tick means a different radius as soon as a layer is added or removed; a stored radius does not
 * move. Deliberately free of any dependency on the renderer, the layer list or Swing, so the
 * evaluator can be exercised headlessly (extra/test/AutomationTrackCheck.java).
 *
 * <p>"Key", not "keyframe": in this codebase a keyframe is already an intra-coded video frame
 * (ExportWriter, and the "Every frame a keyframe" checkbox), and a bug report should have one
 * meaning.
 */
public final class Track {

    /** How the segment LEAVING a key reaches the next one. No Bezier handles: they need their own gesture vocabulary. */
    public enum Interp {
        HOLD, LINEAR, SMOOTH;

        static Interp of(String name) {
            for (Interp i : values())
                if (i.name().equalsIgnoreCase(name))
                    return i;
            return LINEAR;
        }
    }

    public record Key(long time, double value, Interp interp) {}

    private static final Comparator<Key> BY_TIME = Comparator.comparingLong(Key::time);

    public final String paramKey;
    private final ArrayList<Key> keys = new ArrayList<>();
    private boolean enabled = true;

    public Track(String _paramKey) {
        paramKey = _paramKey;
    }

    public List<Key> getKeys() {
        return List.copyOf(keys);
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean _enabled) {
        enabled = _enabled;
    }

    /** Adds a key, replacing any key already at that exact time. Keeps the list sorted. */
    public void put(Key key) {
        keys.removeIf(k -> k.time() == key.time());
        keys.add(key);
        keys.sort(BY_TIME);
    }

    /**
     * The parameter's value at data time {@code t}, or NaN if the track has no keys.
     *
     * <p>Clamped at both ends rather than extrapolated: continuing an opacity ramp past the end of
     * a movie is only a way to arrive at a black screen.
     */
    public double valueAt(long t) {
        int n = keys.size();
        if (n == 0)
            return Double.NaN;
        Key first = keys.get(0);
        if (t <= first.time())
            return first.value();
        Key last = keys.get(n - 1);
        if (t >= last.time())
            return last.value();

        int lo = 0, hi = n - 1; // keys.get(lo).time() <= t < keys.get(hi).time() holds throughout
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (keys.get(mid).time() <= t)
                lo = mid;
            else
                hi = mid;
        }
        Key a = keys.get(lo), b = keys.get(hi);
        if (a.interp() == Interp.HOLD)
            return a.value();
        double u = (double) (t - a.time()) / (b.time() - a.time());
        if (a.interp() == Interp.SMOOTH)
            u = u * u * (3 - 2 * u); // smoothstep: flat at both keys
        return a.value() + u * (b.value() - a.value());
    }

    public JSONObject toJson() {
        JSONArray ja = new JSONArray();
        for (Key k : keys)
            ja.put(new JSONObject()
                    .put("t", TimeUtils.format(k.time()))
                    .put("v", k.value())
                    .put("i", k.interp().name()));
        return new JSONObject().put("key", paramKey).put("enabled", enabled).put("keys", ja);
    }

    /** Null when the object names no parameter; a track whose keys are all unparseable comes back empty, not null. */
    public static Track fromJson(JSONObject jo) {
        String paramKey = jo.optString("key", null);
        if (paramKey == null || paramKey.isBlank())
            return null;
        Track track = new Track(paramKey);
        track.enabled = jo.optBoolean("enabled", true);
        JSONArray ja = jo.optJSONArray("keys");
        if (ja != null)
            for (Object o : ja) {
                if (!(o instanceof JSONObject jk))
                    continue;
                long t;
                try {
                    // TimeUtils.parse, not optParse: these strings are machine-written by
                    // TimeUtils.format, so the strict ISO parse round-trips them to the
                    // millisecond. optParse would route them through SPICE and a
                    // natural-language fallback, which needs a native library no headless check
                    // can load, and which would guess at a malformed field rather than skip it.
                    t = TimeUtils.parse(jk.optString("t", ""));
                } catch (Exception e) {
                    continue; // an unparseable key is dropped; the rest of the track still loads
                }
                track.put(new Key(t, jk.optDouble("v", 0), Interp.of(jk.optString("i", "LINEAR"))));
            }
        return track;
    }

}
