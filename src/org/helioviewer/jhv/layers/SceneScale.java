package org.helioviewer.jhv.layers;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.display.DisplayController;

import org.json.JSONObject;

/**
 * The scene-wide sizes of the 3D Scene pane: how much every placed model is enlarged and the
 * smallest it is drawn on screen. Each model layer's own Enlarge and Min size are relative to
 * these (ModelPlacement.drawnScale), so one slider sizes the whole scene and a layer can still
 * stand out.
 *
 * <p>Saved in each session ("scene" in the .jhv); a session saved before this existed opens at
 * the built-in sizes (1x, 24 px), which is exactly how it was drawn then. Settings hold the
 * sizes a new session starts with (Settings, 3D scene).
 */
public final class SceneScale {

    public static final double BUILT_IN_ENLARGE = 1;
    public static final double BUILT_IN_MIN_PIXELS = ModelPlacement.DEFAULT_MIN_PIXELS;
    public static final double MAX_MIN_PIXELS = 50;
    static final String KEY_ENLARGE = "scene.enlarge";
    static final String KEY_MIN_PIXELS = "scene.minPixels";

    private static final SceneScale INSTANCE = new SceneScale();

    private double enlarge = defaultEnlarge();
    private double minPixels = defaultMinPixels();
    private final List<Runnable> listeners = new ArrayList<>();

    private SceneScale() {}

    public static double enlarge() {
        return INSTANCE.enlarge;
    }

    public static double minPixels() {
        return INSTANCE.minPixels;
    }

    public static void setEnlarge(double v) {
        INSTANCE.enlarge = clampEnlarge(v);
        changed();
    }

    public static void setMinPixels(double v) {
        INSTANCE.minPixels = clampMinPixels(v);
        changed();
    }

    /** Called with no argument when either size changes, so the pane's controls can follow a session load. */
    public static void addListener(Runnable r) {
        INSTANCE.listeners.add(r);
    }

    private static void changed() {
        INSTANCE.listeners.forEach(Runnable::run);
        DisplayController.display();
    }

    static double clampEnlarge(double v) {
        return Double.isFinite(v) ? Math.clamp(v, 1, Math.pow(10, ModelPlacement.MAX_EXAGGERATION_DECADES)) : BUILT_IN_ENLARGE;
    }

    static double clampMinPixels(double v) {
        return Double.isFinite(v) ? Math.clamp(v, 0, MAX_MIN_PIXELS) : BUILT_IN_MIN_PIXELS;
    }

    // ---- the session ----

    public static JSONObject toJson() {
        return new JSONObject().put("enlarge", INSTANCE.enlarge).put("minPixels", INSTANCE.minPixels);
    }

    /** Null (a session from before the 3D Scene pane) restores the built-in sizes, not the Settings defaults, so it looks as it did. */
    public static void fromJson(@Nullable JSONObject jo) {
        INSTANCE.enlarge = clampEnlarge(jo == null ? BUILT_IN_ENLARGE : jo.optDouble("enlarge", BUILT_IN_ENLARGE));
        INSTANCE.minPixels = clampMinPixels(jo == null ? BUILT_IN_MIN_PIXELS : jo.optDouble("minPixels", BUILT_IN_MIN_PIXELS));
        changed();
    }

    // ---- the defaults a new session starts with ----

    public static double defaultEnlarge() {
        return clampEnlarge(parse(Settings.getProperty(KEY_ENLARGE), BUILT_IN_ENLARGE));
    }

    public static double defaultMinPixels() {
        return clampMinPixels(parse(Settings.getProperty(KEY_MIN_PIXELS), BUILT_IN_MIN_PIXELS));
    }

    public static void setDefaultEnlarge(double v) {
        Settings.setProperty(KEY_ENLARGE, Double.toString(clampEnlarge(v)));
    }

    public static void setDefaultMinPixels(double v) {
        Settings.setProperty(KEY_MIN_PIXELS, Double.toString(clampMinPixels(v)));
    }

    private static double parse(@Nullable String s, double fallback) {
        if (s == null)
            return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

}
