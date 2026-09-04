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
 * <p>The compositor engages EDR only once content exceeds roughly 1.25 (measured 2026-09-04,
 * extra/test/native/edr_present_probe.m), and reports a headroom of 1 until then. So on a screen
 * that could offer more, auto starts one frame at {@link #BOOTSTRAP}, the renderer repaints when
 * the reading rises, and from then on auto tracks the real value.
 */
public final class HdrGain {

    private static final String KEY_GAIN = "display.hdrGain";
    private static final String KEY_CANVAS = "display.edrCanvas";
    private static final float MAX = 16;
    static final float BOOTSTRAP = 1.5f;

    private static String setting = defaultSetting();

    private static String defaultSetting() {
        String stored = Settings.getProperty(KEY_GAIN);
        return stored == null || stored.isBlank() ? "auto" : stored.trim();
    }

    /** The gain the shader should apply now: 1 while capturing or without an EDR canvas. */
    public static float current(boolean capturing) {
        return resolve(setting, Display.edrHeadroom, Display.edrPotential, capturing || !Display.edrCanvas);
    }

    /**
     * Pure: the gain for a setting, the screen's current and potential headroom, and whether the
     * target is SDR (a capture, or no EDR canvas).
     */
    static float resolve(String _setting, double headroom, double potential, boolean sdr) {
        if (sdr)
            return 1;
        float auto = headroom > 1 ? (float) headroom : potential > 1 ? BOOTSTRAP : 1;
        if (_setting == null || "auto".equals(_setting))
            return auto;
        try {
            return (float) Math.clamp(Double.parseDouble(_setting), 1, MAX);
        } catch (NumberFormatException e) {
            return auto;
        }
    }

    public static String setting() {
        return setting;
    }

    public static void setSetting(String _setting) {
        setting = _setting == null || _setting.isBlank() ? "auto" : _setting.trim();
        Settings.setProperty(KEY_GAIN, setting);
    }

    /** Whether the EDR rung is asked for at the next canvas attach; the renderer reads the same key. */
    public static boolean canvasEnabled() {
        return !"false".equals(Settings.getProperty(KEY_CANVAS));
    }

    public static void setCanvasEnabled(boolean enabled) {
        Settings.setProperty(KEY_CANVAS, Boolean.toString(enabled));
    }

    private HdrGain() {}
}
