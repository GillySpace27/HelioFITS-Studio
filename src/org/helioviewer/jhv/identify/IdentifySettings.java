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
    public static final String GLOW = "identify.glow";
    public static final String PULSE = "identify.pulse";
    public static final String ICON_BY_KIND = "identify.icon.byKind";
    public static final String ICON_COLOR = "identify.icon.color";
    public static final String TOOLTIP_BY_KIND = "identify.tooltip.byKind";
    public static final String SYMBOLS_INSTEAD = "identify.symbols.instead";
    public static final String SYMBOLS_ALWAYS = "identify.symbols.always";
    public static final String HIGHLIGHT_ALL = "identify.highlightAll";
    public static final String DATA_COMETS = "identify.data.comets";
    public static final String DATA_CMES = "identify.data.cmes";
    public static final String DATA_CLUSTERS = "identify.data.clusters";
    public static final String DATA_GALAXIES = "identify.data.galaxies";
    public static final String DATA_NEBULAE = "identify.data.nebulae";
    public static final String DEEP_SKY_MAX_MAG = "identify.data.deepSky.maxMagnitude";

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

    /** The colour the icon takes when it is not coloured by kind: the soft blue-white it always had (Gilly, 2026-11-08). */
    public static final String DEFAULT_ICON_COLOR = "#96D7FF";
    /**
     * Deep-sky objects fainter than this are left out unless they carry a Messier number or a
     * common name in OpenNGC: past it the list is thousands of faint galaxies nobody asked to have
     * named (judgement).
     */
    public static final double DEFAULT_DEEP_SKY_MAX_MAG = 9;

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

    /** A soft glow along the outline of what is named under the pointer (Gilly, 2026-11-08: on by default). */
    public static boolean glow() {
        return bool(GLOW, true);
    }

    /** A short pulse outward from that outline when the pointer reaches something new. */
    public static boolean pulse() {
        return bool(PULSE, true);
    }

    /** Icon and name colour follow the kind of object (stars white, planets green, comets red, ...). */
    public static boolean iconByKind() {
        return bool(ICON_BY_KIND, true);
    }

    /** The one colour of every icon when it is not coloured by kind, as 0xRRGGBB. */
    public static int iconColor() {
        String v = Settings.getProperty(ICON_COLOR);
        try {
            return v == null ? Integer.decode(DEFAULT_ICON_COLOR) : Integer.decode(v) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return Integer.decode(DEFAULT_ICON_COLOR);
        }
    }

    /** The icon's red, green and blue for an object of {@code kind}. */
    public static int[] iconRgb(Kind kind) {
        if (iconByKind())
            return kind.rgb;
        int c = iconColor();
        return new int[]{c >> 16 & 255, c >> 8 & 255, c & 255};
    }

    /** The label beside the pointer is written in the colour of the icon it describes (Gilly, 2026-11-10: the default). */
    public static boolean tooltipByKind() {
        return bool(TOOLTIP_BY_KIND, true);
    }

    /** A planet's highlight is its astronomical symbol in place of the ring. */
    public static boolean symbolsInstead() {
        return bool(SYMBOLS_INSTEAD, false);
    }

    /** Every planet and the Moon in view carries its symbol all the time, with no hover (Highlight All is not needed). */
    public static boolean symbolsAlways() {
        return bool(SYMBOLS_ALWAYS, false);
    }

    public static boolean highlightAll() {
        return bool(HIGHLIGHT_ALL, false);
    }

    public static boolean dataComets() {
        return bool(DATA_COMETS, false);
    }

    public static boolean dataCmes() {
        return bool(DATA_CMES, false);
    }

    public static boolean dataClusters() {
        return bool(DATA_CLUSTERS, false);
    }

    public static boolean dataGalaxies() {
        return bool(DATA_GALAXIES, false);
    }

    public static boolean dataNebulae() {
        return bool(DATA_NEBULAE, false);
    }

    public static double deepSkyMaxMagnitude() {
        return number(DEEP_SKY_MAX_MAG, DEFAULT_DEEP_SKY_MAX_MAG, -2, 20);
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

    /**
     * Put every number and finer switch back to its default, leaving the three main switches
     * (identify, tooltip, stars) as they are: resetting is for a setup that went wrong, and
     * switching the feature off as a side effect would hide the evidence.
     */
    public static void resetToDefaults() {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put(TOLERANCE_PX, String.valueOf(DEFAULT_TOLERANCE_PX));
        values.put(DWELL_MS, String.valueOf((double) DEFAULT_DWELL_MS));
        values.put(EARTH_UNCERTAINTY_KM, String.valueOf(DEFAULT_EARTH_UNCERTAINTY_KM));
        values.put(STAR_MAX_MAG, String.valueOf(DEFAULT_STAR_MAX_MAG));
        values.put(LIGHT_TIME, "false");
        values.put(PLUTO, "true");
        values.put(STAR_NAMES_IAU, "true");
        values.put(STAR_NAMES_BAYER, "true");
        values.put(GLOW, "true");
        values.put(PULSE, "true");
        values.put(ICON_BY_KIND, "true");
        values.put(ICON_COLOR, DEFAULT_ICON_COLOR);
        values.put(TOOLTIP_BY_KIND, "true");
        values.put(SYMBOLS_INSTEAD, "false");
        values.put(SYMBOLS_ALWAYS, "false");
        values.put(DEEP_SKY_MAX_MAG, String.valueOf(DEFAULT_DEEP_SKY_MAX_MAG));
        Settings.setProperties(values); // one write, not one per key
        fire();
    }

    /** What the identifier is set to, for the identifySet command's answer. */
    public static org.json.JSONObject toJson() {
        return new org.json.JSONObject()
                .put("enabled", enabled()).put("tooltip", tooltip()).put("stars", stars())
                .put("starNamesIau", starNamesIau()).put("starNamesBayer", starNamesBayer())
                .put("starMaxMagnitude", starMaxMagnitude()).put("tolerancePixels", tolerancePixels())
                .put("tooltipDwellMillis", dwellMillis()).put("lightTime", lightTime())
                .put("earthObserverUncertaintyKm", earthUncertaintyKm()).put("pluto", pluto())
                .put("glow", glow()).put("pulse", pulse())
                .put("tooltipByKind", tooltipByKind()).put("symbolsInstead", symbolsInstead()).put("symbolsAlways", symbolsAlways())
                .put("iconByKind", iconByKind()).put("iconColor", String.format("#%06X", iconColor()))
                .put("highlightAll", highlightAll()).put("comets", dataComets()).put("cmes", dataCmes())
                .put("clusters", dataClusters()).put("galaxies", dataGalaxies()).put("nebulae", dataNebulae())
                .put("deepSkyMaxMagnitude", deepSkyMaxMagnitude());
    }

    private IdentifySettings() {}
}
