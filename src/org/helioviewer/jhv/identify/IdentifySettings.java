package org.helioviewer.jhv.identify;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.helioviewer.jhv.app.Settings;

/**
 * The identifier's switches and numbers, kept in the user's settings file.
 *
 * <p>The three switches are in the View menu under Show Status Bar (the footer's only other
 * setting); the numbers are in Settings &gt; Identify. Every default is a judgement, stated beside
 * it, and every one can be changed.
 */
public final class IdentifySettings {

    public static final String ENABLED = "identify.enabled";
    public static final String TOOLTIP = "identify.tooltip";
    public static final String STARS = "identify.stars";
    public static final String STAR_NAMES_IAU = "identify.starNames.iau";
    public static final String STAR_NAMES_BAYER = "identify.starNames.bayer";
    public static final String STAR_MAX_MAG = "identify.stars.maxMagnitude";
    public static final String TOLERANCE_PX = "identify.tolerancePixels";
    public static final String DWELL_MS = "identify.tooltipDwellMillis";
    public static final String LIGHT_TIME = "identify.lightTime";
    public static final String EARTH_UNCERTAINTY_KM = "identify.earthObserverUncertaintyKm";
    public static final String PLUTO = "identify.pluto";

    /** Off: the footer looks as it always has until someone asks for this. */
    static final boolean DEFAULT_ENABLED = false;
    /** Six screen pixels: about the size of a pointer's hot spot plus a hand's tremor (judgement). */
    public static final double DEFAULT_TOLERANCE_PX = 6;
    /** Long enough that sweeping across the field does not flash labels (judgement). */
    public static final int DEFAULT_DWELL_MS = 400;
    /**
     * How far a near-Earth observer may really be from where its header says. PUNCH L3 mosaics put
     * the observer at Earth's centre (HGLN_OBS 0, GEOX_OBS empty) while the spacecraft are in a
     * low orbit; 7,400 km is a low-orbit radius from Earth's centre, rounded up (estimate). It only
     * matters for the Moon, where it is worth about a degree.
     */
    public static final double DEFAULT_EARTH_UNCERTAINTY_KM = 7400;
    /** Naked-eye limit, as the starfield layer's default (judgement). */
    public static final double DEFAULT_STAR_MAX_MAG = 6;

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static void addListener(Runnable r) {
        listeners.add(r);
    }

    private static void fire() {
        for (Runnable r : listeners)
            r.run();
    }

    private static boolean bool(String key, boolean def) {
        String v = Settings.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private static double number(String key, double def, double min, double max) {
        String v = Settings.getProperty(key);
        if (v == null)
            return def;
        try {
            double d = Double.parseDouble(v);
            return Double.isFinite(d) ? Math.clamp(d, min, max) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static void set(String key, String value) {
        Settings.setProperty(key, value);
        fire();
    }

    public static boolean enabled() {
        return bool(ENABLED, DEFAULT_ENABLED);
    }

    public static boolean tooltip() {
        return bool(TOOLTIP, false);
    }

    public static boolean stars() {
        return bool(STARS, false);
    }

    public static boolean starNamesIau() {
        return bool(STAR_NAMES_IAU, true);
    }

    public static boolean starNamesBayer() {
        return bool(STAR_NAMES_BAYER, true);
    }

    public static double starMaxMagnitude() {
        return number(STAR_MAX_MAG, DEFAULT_STAR_MAX_MAG, -2, 12);
    }

    public static double tolerancePixels() {
        return number(TOLERANCE_PX, DEFAULT_TOLERANCE_PX, 0, 100);
    }

    public static int dwellMillis() {
        return (int) number(DWELL_MS, DEFAULT_DWELL_MS, 0, 5000);
    }

    public static boolean lightTime() {
        return bool(LIGHT_TIME, false);
    }

    public static double earthUncertaintyKm() {
        return number(EARTH_UNCERTAINTY_KM, DEFAULT_EARTH_UNCERTAINTY_KM, 0, 1e6);
    }

    public static boolean pluto() {
        return bool(PLUTO, true);
    }

    public static void setEnabled(boolean b) {
        set(ENABLED, Boolean.toString(b));
    }

    public static void setTooltip(boolean b) {
        set(TOOLTIP, Boolean.toString(b));
    }

    public static void setStars(boolean b) {
        set(STARS, Boolean.toString(b));
    }

    /** What the identifier is set to, for the identifySet command's answer. */
    public static org.json.JSONObject toJson() {
        return new org.json.JSONObject()
                .put("enabled", enabled()).put("tooltip", tooltip()).put("stars", stars())
                .put("starNamesIau", starNamesIau()).put("starNamesBayer", starNamesBayer())
                .put("starMaxMagnitude", starMaxMagnitude()).put("tolerancePixels", tolerancePixels())
                .put("tooltipDwellMillis", dwellMillis()).put("lightTime", lightTime())
                .put("earthObserverUncertaintyKm", earthUncertaintyKm()).put("pluto", pluto());
    }

    private IdentifySettings() {}
}
