package org.helioviewer.jhv.app;

public class DisplaySettings {

    public enum TimeMode {Observer, Sun, Earth}

    private static boolean autoResetView = true;
    private static final java.util.concurrent.atomic.AtomicBoolean fitNewMultiviewLayers = new java.util.concurrent.atomic.AtomicBoolean(true);
    private static boolean normalizeAIA;
    private static boolean normalizeRadius;
    private static TimeMode timeMode = TimeMode.Observer;

    static {
        // On unless it has been turned off: a freshly opened dataset framed by whatever the last
        // session left behind is the state people reach for Reset View to escape.
        autoResetView = parseDefaultOn(Settings.getProperty("display.autoResetView"));
        fitNewMultiviewLayers.set(parseDefaultOn(Settings.getProperty("display.fitNewMultiviewLayers")));
        normalizeAIA = Boolean.parseBoolean(Settings.getProperty("display.normalizeAIA"));
        normalizeRadius = Boolean.parseBoolean(Settings.getProperty("display.normalizeRadius"));

        try {
            timeMode = TimeMode.valueOf(Settings.getProperty("display.time"));
        } catch (Exception ignore) {}
    }

    /**
     * How the stored value reads, including the absent one: a preferences file written before this
     * existed, or a fresh install, must arrive with the setting on. Boolean.parseBoolean answers
     * false to anything it does not recognise, nothing at all included, so it cannot decide this.
     */
    public static boolean parseDefaultOn(String stored) {
        return stored == null || stored.isBlank() || Boolean.parseBoolean(stored);
    }

    /** Whether a layer's first frame resets the view, as the Reset View button does. */
    public static boolean getAutoResetView() {
        return autoResetView;
    }

    public static void setAutoResetView(boolean b) {
        Settings.setProperty("display.autoResetView", Boolean.toString(b));
        autoResetView = b;
    }

    /** Whether a layer that lands in multiview is zoomed to fit its own viewport. */
    public static boolean getFitNewMultiviewLayers() {
        return fitNewMultiviewLayers.get();
    }

    public static void setFitNewMultiviewLayers(boolean b) {
        Settings.setProperty("display.fitNewMultiviewLayers", Boolean.toString(b));
        fitNewMultiviewLayers.set(b);
    }

    public static boolean getNormalizeAIA() {
        return normalizeAIA;
    }

    public static void setNormalizeAIA(boolean b) {
        Settings.setProperty("display.normalizeAIA", Boolean.toString(b));
        normalizeAIA = b;
    }

    public static boolean getNormalizeRadius() {
        return normalizeRadius;
    }


    public static void setNormalizeRadius(boolean b) {
        Settings.setProperty("display.normalizeRadius", Boolean.toString(b));
        normalizeRadius = b;
    }

    public static TimeMode getTimeMode() {
        return timeMode;
    }

    public static void setTimeMode(TimeMode mode) {
        Settings.setProperty("display.time", mode.toString());
        timeMode = mode;
    }

}
