package org.helioviewer.jhv.gui.component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import org.helioviewer.jhv.display.MapMode;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Study table's columns as text: how each one reads a view or a row's override, how typed text
 * becomes an override value, why a value is refused, and the tab-separated paste format. No Swing,
 * so the self-check can drive all of it headless.
 *
 * <p>Blank text, or "inherit", means the row inherits: nothing is stored for it.
 */
public final class StudyCells {

    public enum Col {
        NAME("Name"), PROJECTION("Projection"), LAMBDA("Lambda"), FOV("FOV"), ZOOM("Zoom"), CENTER("Center"),
        ROTATION("Rotation"), LAYERS("Layers"), SIZE("Size"), ENABLED("Enabled");

        public final String header;

        Col(String header) {
            this.header = header;
        }

        /** Columns that live in the override and can be inherited. */
        public boolean inheritable() {
            return this != NAME && this != ENABLED;
        }
    }

    public static final String INHERIT = "inherit";

    // Camera.MIN_FOV and MAX_FOV (display/Camera.java), in degrees: the camera clamps to these.
    static final double MIN_FOV_DEG = 1. / 360, MAX_FOV_DEG = 120;

    /** A parsed cell: value null and reason null is blank (inherit); reason non-null is refused text. */
    public record Parsed(@Nullable Object value, @Nullable String reason) {
        static Parsed blank() {
            return new Parsed(null, null);
        }

        static Parsed bad(String reason) {
            return new Parsed(null, reason);
        }

        public boolean isBlank() {
            return value == null && reason == null;
        }
    }

    private StudyCells() {
    }

    public static boolean isBlank(@Nullable String text) {
        return text == null || text.isBlank() || text.strip().equalsIgnoreCase(INHERIT);
    }

    /** Compact decimal: at most six places, no trailing zeros. */
    static String num(double d) {
        return BigDecimal.valueOf(Math.round(d * 1e6) / 1e6).stripTrailingZeros().toPlainString();
    }

    /** The column's text from a view or an override, null when the key is absent. */
    @Nullable
    public static String format(Col c, JSONObject view) {
        JSONObject cam = view.optJSONObject("camera");
        switch (c) {
            case PROJECTION: {
                String p = view.optString("projection", null);
                if (p == null)
                    return null;
                MapMode m = MapMode.fromName(p);
                return m == null ? p : m.toString();
            }
            case LAMBDA:
                return view.has("warpLambda") ? num(view.optDouble("warpLambda")) : null;
            case FOV:
                return cam != null && cam.has("fov") ? num(Math.toDegrees(cam.optDouble("fov"))) : null;
            case ZOOM:
                return cam != null && cam.has("zoom") ? num(cam.optDouble("zoom")) : null;
            case CENTER: {
                JSONArray t = cam == null ? null : cam.optJSONArray("translation");
                return t == null || t.length() < 2 ? null : num(t.optDouble(0)) + ", " + num(t.optDouble(1));
            }
            case ROTATION: {
                JSONArray q = cam == null ? null : cam.optJSONArray("dragRotation");
                if (q == null || q.length() < 4)
                    return null;
                // Quat.toJson writes [w, x, y, z]; the rotation angle is 2 acos|w|.
                double w = Math.min(1, Math.abs(q.optDouble(0)));
                return BigDecimal.valueOf(Math.round(Math.toDegrees(2 * Math.acos(w)) * 10) / 10.).stripTrailingZeros().toPlainString() + "\u00b0";
            }
            case LAYERS: {
                JSONObject l = view.optJSONObject("layers");
                if (l == null)
                    return null;
                Map<String, Boolean> m = new LinkedHashMap<>();
                for (String k : l.keySet())
                    m.put(k, l.optBoolean(k, true));
                return formatLayers(m);
            }
            case SIZE: {
                JSONArray s = view.optJSONArray("size");
                return s == null || s.length() < 2 ? null : s.optInt(0) + "x" + s.optInt(1);
            }
            default:
                return null;
        }
    }

    public static String formatLayers(Map<String, Boolean> layers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Boolean> e : layers.entrySet()) {
            if (!sb.isEmpty())
                sb.append(", ");
            sb.append(e.getKey()).append(": ").append(e.getValue() ? "on" : "off");
        }
        return sb.toString();
    }

    @Nullable
    private static Double number(String s) {
        try {
            double d = Double.parseDouble(s.strip());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Boolean onOff(String s) {
        return switch (s.strip().toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1", "x" -> Boolean.TRUE;
            case "off", "false", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * Typed text to an override value. layerNames, when not empty, are the scene's layers: a name
     * outside them is refused, since the override would silently match nothing.
     */
    public static Parsed parse(Col c, @Nullable String text, Collection<String> layerNames) {
        if (c == Col.NAME)
            return text == null || text.isBlank() ? Parsed.bad("A name is needed: it goes into the file name.") : new Parsed(text.strip(), null);
        if (c == Col.ENABLED) // a checkbox, never refused: only off, false, no or 0 turn a row off
            return new Parsed(text == null || !Boolean.FALSE.equals(onOff(text)), null);
        if (isBlank(text))
            return Parsed.blank();
        String t = text.strip();
        switch (c) {
            case PROJECTION: {
                MapMode m = MapMode.fromName(t);
                if (m == null)
                    for (MapMode mm : MapMode.values())
                        if (mm.toString().equalsIgnoreCase(t) || mm.name().equalsIgnoreCase(t))
                            m = mm;
                return m == null ? Parsed.bad("No projection called \"" + t + "\"; pick one from the list.") : new Parsed(m.name(), null);
            }
            case LAMBDA: {
                Double d = number(t);
                return d == null || d < -1 || d > 1 ? Parsed.bad("Lambda is a number from -1 to 1.") : new Parsed(d, null);
            }
            case FOV: {
                Double d = number(t);
                return d == null || d < MIN_FOV_DEG || d > MAX_FOV_DEG
                        ? Parsed.bad("FOV is in degrees, from 1/360 to 120.") : new Parsed(Math.toRadians(d), null);
            }
            case ZOOM: {
                Double d = number(t);
                return d == null || d <= 0 ? Parsed.bad("Zoom is a number above 0.") : new Parsed(d, null);
            }
            case CENTER: {
                String[] p = t.split("[,;\\s]+");
                Double x = p.length == 2 ? number(p[0]) : null, y = p.length == 2 ? number(p[1]) : null;
                return x == null || y == null ? Parsed.bad("Center is two numbers: x, y.") : new Parsed(new JSONArray(new double[]{x, y}), null);
            }
            case LAYERS: {
                JSONObject l = new JSONObject();
                for (String part : t.split("[,;]")) {
                    if (part.isBlank())
                        continue;
                    int i = Math.max(part.lastIndexOf(':'), part.lastIndexOf('='));
                    Boolean b = i < 0 ? null : onOff(part.substring(i + 1));
                    String name = i < 0 ? part.strip() : part.substring(0, i).strip();
                    if (b == null || name.isEmpty())
                        return Parsed.bad("Layers are name: on or name: off, separated by commas.");
                    if (!layerNames.isEmpty() && !layerNames.contains(name))
                        return Parsed.bad("No layer named \"" + name + "\" in this scene.");
                    l.put(name, b);
                }
                return l.isEmpty() ? Parsed.blank() : new Parsed(l, null);
            }
            case SIZE: {
                String[] p = t.split("\\s*[xX\u00d7,]\\s*");
                int w = 0, h = 0;
                if (p.length == 2) {
                    try {
                        w = Integer.parseInt(p[0].strip());
                        h = Integer.parseInt(p[1].strip());
                    } catch (NumberFormatException e) {
                        w = 0;
                    }
                }
                return w <= 0 || h <= 0 ? Parsed.bad("Size is width x height in pixels, as 2048x2048.") : new Parsed(new JSONArray(new int[]{w, h}), null);
            }
            case ROTATION:
                return Parsed.bad("Rotation is read only; set it with Update row from view.");
            default:
                return Parsed.blank();
        }
    }

    /** Stores a parsed value, or removes the key for null, in an override. A camera left empty is dropped. */
    public static void put(JSONObject override, Col c, @Nullable Object value) {
        String camKey = switch (c) {
            case FOV -> "fov";
            case ZOOM -> "zoom";
            case CENTER -> "translation";
            case ROTATION -> "dragRotation";
            default -> null;
        };
        if (camKey != null) {
            JSONObject cam = override.optJSONObject("camera");
            if (cam == null)
                cam = new JSONObject();
            if (value == null)
                cam.remove(camKey);
            else
                cam.put(camKey, value);
            if (cam.isEmpty())
                override.remove("camera");
            else
                override.put("camera", cam);
            return;
        }
        String key = switch (c) {
            case PROJECTION -> "projection";
            case LAMBDA -> "warpLambda";
            case LAYERS -> "layers";
            case SIZE -> "size";
            default -> null;
        };
        if (key == null)
            return;
        if (value == null)
            override.remove(key);
        else
            override.put(key, value);
    }

    /** One pasted line: the values that parsed and the text of the cells that did not. */
    public record PastedRow(String name, boolean enabled, JSONObject override, Map<Col, String> bad) {
    }

    public record Paste(List<PastedRow> rows, List<String> problems) {
    }

    @Nullable
    static Col headerCol(String h) {
        String k = h.strip().toLowerCase(Locale.ROOT);
        for (Col c : Col.values())
            if (c.header.toLowerCase(Locale.ROOT).equals(k))
                return c;
        return switch (k) {
            case "warplambda", "\u03bb" -> Col.LAMBDA;
            case "translation" -> Col.CENTER;
            case "on" -> Col.ENABLED;
            default -> null;
        };
    }

    /**
     * Tab-separated rows under one header line naming the columns, in any order and any subset.
     * Rotation is read only and skipped; a column the header does not know is reported and skipped.
     */
    public static Paste parsePaste(String text, Collection<String> layerNames, int firstNumber) {
        List<PastedRow> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int i = 0;
        while (i < lines.length && lines[i].isBlank())
            i++;
        if (i == lines.length) {
            problems.add("Nothing to paste.");
            return new Paste(rows, problems);
        }
        String[] head = lines[i].split("\t", -1);
        Col[] cols = new Col[head.length];
        int known = 0;
        for (int j = 0; j < head.length; j++) {
            cols[j] = headerCol(head[j]);
            if (cols[j] != null)
                known++;
            else if (!head[j].isBlank())
                problems.add("Column \"" + head[j].strip() + "\" is not a Study column; skipped.");
        }
        if (known == 0) {
            problems.clear();
            problems.add("The first line must be a header naming the columns (Name, Projection, Lambda, ...), tab separated.");
            return new Paste(rows, problems);
        }
        int n = firstNumber;
        for (i++; i < lines.length; i++) {
            if (lines[i].isBlank())
                continue;
            String[] cells = lines[i].split("\t", -1);
            String name = "row " + n;
            boolean enabled = true;
            JSONObject override = new JSONObject();
            Map<Col, String> bad = new EnumMap<>(Col.class);
            for (int j = 0; j < cols.length && j < cells.length; j++) {
                Col c = cols[j];
                if (c == null || c == Col.ROTATION)
                    continue;
                Parsed p = parse(c, cells[j], layerNames);
                if (p.reason() != null) {
                    if (c == Col.NAME) // a blank name falls back to the row number
                        continue;
                    bad.put(c, cells[j]);
                } else if (c == Col.NAME)
                    name = (String) p.value();
                else if (c == Col.ENABLED)
                    enabled = (Boolean) p.value();
                else if (p.value() != null)
                    put(override, c, p.value());
            }
            rows.add(new PastedRow(name, enabled, override, bad));
            n++;
        }
        if (rows.isEmpty())
            problems.add("The header has no rows under it.");
        return new Paste(rows, problems);
    }
}
