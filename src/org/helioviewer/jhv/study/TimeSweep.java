package org.helioviewer.jhv.study;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.view.View;
import org.json.JSONArray;
import org.json.JSONObject;

/** A time sweep: {"times":[...]}, {"start","step","count"} (step like 30s, 5m, 6h, 1d) or {"frames":"master"}. */
final class TimeSweep extends Sweep {

    private static final Pattern STEP = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([smhd])");

    TimeSweep(JSONObject raw) {
        super(raw);
    }

    /** The times, in UTC milliseconds, in the order they are rendered. */
    List<Long> times() {
        List<Long> l = new ArrayList<>();
        JSONArray a = raw.optJSONArray("times");
        if (a != null) {
            for (int i = 0; i < a.length(); i++)
                l.add(parseTime(a.getString(i)));
        } else if ("master".equals(raw.optString("frames", ""))) {
            l.addAll(masterTimes());
        } else if (raw.has("start")) {
            long t = parseTime(raw.getString("start"));
            long step = parseStep(raw.optString("step", ""));
            for (int i = 0, n = raw.optInt("count", 1); i < n; i++)
                l.add(t + i * step);
        }
        return l;
    }

    @Override
    int size() {
        return times().size();
    }

    static long parseTime(String s) {
        String t = s.trim();
        if (t.endsWith("Z") || t.matches(".*[+-]\\d\\d:\\d\\d$"))
            return Instant.parse(t.endsWith("Z") ? t : java.time.OffsetDateTime.parse(t).toInstant().toString()).toEpochMilli();
        return LocalDateTime.parse(t.length() == 10 ? t + "T00:00:00" : t).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** Milliseconds of "30s", "5m", "6h", "1d". */
    static long parseStep(String s) {
        Matcher m = STEP.matcher(s.trim());
        if (!m.matches())
            throw new IllegalArgumentException("A time step looks like 30s, 5m, 6h or 1d, not \"" + s + "\".");
        double n = Double.parseDouble(m.group(1));
        Duration unit = switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(1);
            case "m" -> Duration.ofMinutes(1);
            case "h" -> Duration.ofHours(1);
            default -> Duration.ofDays(1);
        };
        return (long) (n * unit.toMillis());
    }

    /** Every frame time of the master layer; the one place a time sweep touches the live scene. Empty with no layer. */
    static List<Long> masterTimes() {
        List<Long> l = new ArrayList<>();
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer == null)
            return l;
        View view = layer.getView();
        for (int i = 0, max = view.getMaximumFrameNumber(); i <= max; i++)
            l.add(view.getFrameTime(i).milli);
        return l;
    }
}
