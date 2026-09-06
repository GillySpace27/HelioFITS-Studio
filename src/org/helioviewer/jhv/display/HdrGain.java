package org.helioviewer.jhv.display;

import org.helioviewer.jhv.app.Settings;

/**
 * How far into the display's EDR headroom image layers are pushed.
 *
 * <p>A flat gain on the colour after the colour table (decided 2026-09-04, docs/edr-canvas-brief.md):
 * "auto" means the screen's current headroom, so LUT white lands on peak brightness and the rest
 * scales with it; a number is a fixed multiple of SDR white. Overlays never see it, and neither
 * does any capture path: what is exported is exactly what the 10-bit canvas would have shown.
 *
 * <p>The compositor engages EDR only once something on screen exceeds roughly 1.25 (measured
 * 2026-09-04, extra/test/native/edr_present_probe.m) and reports a headroom of 1 until then. The
 * Metal presenter draws a 3x3 patch above white in a corner until that happens, the renderer
 * repaints when the reading rises, and the gain here is simply capped by what the screen reports.
 */
public final class HdrGain {

    /** How the gain is applied; the shader's hdrMode is the ordinal. */
    public enum Mode {
        Linear("Linear (whole image scaled)"),
        HardKnee("Hard knee (brightest data only, straight)"),
        SoftKnee("Soft knee (brightest data only, rolls to white)"),
        BeyondRange("Beyond the display range (data above the range shine over white)"),
        Uniform("Uniform lightness (even steps; the headroom goes to data above the range)");

        public final String label;

        Mode(String _label) {
            label = _label;
        }

        static Mode fromName(String name) {
            for (Mode m : values())
                if (m.name().equalsIgnoreCase(name))
                    return m;
            return BeyondRange;
        }
    }

    private static final String KEY_GAIN = "display.hdrGain";
    private static final String KEY_MODE = "display.hdrMode";
    private static final String KEY_KNEE = "display.hdrKnee";
    private static final String KEY_IN_RANGE = "display.hdrInRange";
    private static final String KEY_CANVAS = "display.edrCanvas";
    private static final float MAX = 16;

    private static String setting = defaultSetting();
    private static Mode mode = Mode.fromName(String.valueOf(Settings.getProperty(KEY_MODE)));
    private static float knee = parseKnee(Settings.getProperty(KEY_KNEE));
    private static float inRange = parseInRange(Settings.getProperty(KEY_IN_RANGE));

    private static float parseInRange(String stored) {
        try {
            return (float) Math.clamp(Double.parseDouble(stored), 0, 1);
        } catch (NumberFormatException | NullPointerException e) {
            return 0.35f;
        }
    }

    private static float parseKnee(String stored) {
        try {
            return (float) Math.clamp(Double.parseDouble(stored), 0.05, 0.95);
        } catch (NumberFormatException | NullPointerException e) {
            return 0.75f;
        }
    }

    // One stop over white by default. The panel can do far more, and "auto" (the display's
    // maximum) is still offered, but a full-headroom default is a demo, not a picture.
    private static String defaultSetting() {
        String stored = Settings.getProperty(KEY_GAIN);
        return stored == null || stored.isBlank() ? "2" : stored.trim();
    }

    /** The gain the shader should apply now: 1 while capturing or without an EDR canvas. */
    public static float current(boolean capturing) {
        return resolve(setting, Display.edrHeadroom, Display.edrPotential, capturing || !Display.edrCanvas);
    }

    /**
     * Pure: the gain for a setting, the screen's current and potential headroom, and whether the
     * target is SDR (a capture, or no EDR canvas). A fixed stop is never more than the screen can
     * show: past the headroom the compositor clips to peak white, which is the blown-out look.
     */
    static float resolve(String _setting, double headroom, double potential, boolean sdr) {
        if (sdr)
            return 1;
        float available = headroom > 1 ? (float) headroom : 1;
        if (_setting == null || "auto".equals(_setting))
            return available;
        try {
            return Math.min((float) Math.clamp(Double.parseDouble(_setting), 1, MAX), available);
        } catch (NumberFormatException e) {
            return available;
        }
    }

    public static String setting() {
        return setting;
    }

    public static Mode mode() {
        return mode;
    }

    public static void setMode(Mode _mode) {
        mode = _mode;
        Settings.setProperty(KEY_MODE, mode.name());
    }

    /** Where the knee sits, as a fraction of the data range feeding the colour table; ignored by Linear. */
    public static float knee() {
        return knee;
    }

    public static void setKnee(double _knee) {
        knee = (float) Math.clamp(_knee, 0.05, 0.95);
        Settings.setProperty(KEY_KNEE, Float.toString(knee));
    }

    /**
     * How much of the headroom Uniform spends INSIDE the display range, as a fraction.
     *
     * <p>0 puts all of it above the range: the picture is then exactly what it is with no headroom
     * at all, and only data that exceeds the range shines. 1 puts all of it inside: the range
     * itself reaches the display's peak and there is nothing left over it, so over-range data goes
     * flat again. In between, the top of the range lands at L* = 100 + s (Lmax - 100) and the rest
     * of the climb continues above it. Ignored by every mode but Uniform.
     */
    public static float inRange() {
        return inRange;
    }

    public static void setInRange(double _inRange) {
        inRange = (float) Math.clamp(_inRange, 0, 1);
        Settings.setProperty(KEY_IN_RANGE, Float.toString(inRange));
    }

    public static void setSetting(String _setting) {
        setting = _setting == null || _setting.isBlank() ? "auto" : _setting.trim();
        Settings.setProperty(KEY_GAIN, setting);
    }

    /** Whether the EDR rung is asked for at the next canvas attach; the renderer reads the same key. */
    public static boolean canvasEnabled() {
        return !"false".equals(Settings.getProperty(KEY_CANVAS));
    }

    /**
     * Turning the canvas off also takes the brightness to 1x, and turning it back on restores what
     * it was.
     *
     * <p>The canvas itself is created at startup, so on its own the flag did nothing until the next
     * launch and read as broken. The gain, by contrast, is a uniform and changes the picture now.
     * Forcing it to 1x is the belt to the flag's suspenders: with both, "HDR off" is visibly off at
     * once, and the remembered brightness comes back with the canvas rather than being lost.
     */
    public static void setCanvasEnabled(boolean enabled) {
        boolean was = canvasEnabled();
        Settings.setProperty(KEY_CANVAS, Boolean.toString(enabled));
        if (enabled == was)
            return;
        if (!enabled) {
            Settings.setProperty(KEY_GAIN_BEFORE_OFF, setting);
            setSetting("1");
        } else {
            Object remembered = Settings.getProperty(KEY_GAIN_BEFORE_OFF);
            if (remembered != null && !"1".equals(String.valueOf(remembered)))
                setSetting(String.valueOf(remembered));
        }
    }

    private static final String KEY_GAIN_BEFORE_OFF = "display.hdrGainBeforeOff";

    private HdrGain() {}
}
