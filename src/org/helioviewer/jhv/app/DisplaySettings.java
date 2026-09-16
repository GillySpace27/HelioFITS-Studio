package org.helioviewer.jhv.app;

public class DisplaySettings {

    public enum TimeMode {Observer, Sun, Earth}

    private static boolean normalizeAIA;
    private static boolean normalizeRadius;
    private static TimeMode timeMode = TimeMode.Observer;

    static {
        normalizeAIA = Boolean.parseBoolean(Settings.getProperty("display.normalizeAIA"));
        normalizeRadius = Boolean.parseBoolean(Settings.getProperty("display.normalizeRadius"));

        try {
            timeMode = TimeMode.valueOf(Settings.getProperty("display.time"));
        } catch (Exception ignore) {}
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
