package org.helioviewer.jhv.opengl.angle;

import java.awt.Canvas;
import java.awt.GraphicsConfiguration;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;

/**
 * The projector mirror in HDR: when the projector reports EDR headroom, each frame the main EDR
 * canvas presents is drawn again into a second EDR layer on the projector's window, straight from
 * the canvas IOSurface (Gilly, 2026-10-06: presenting must not be useless for HDR content). The
 * 8-bit readback mirror stays for SDR projectors.
 *
 * <p>The gain is applied once per frame for both screens, so while this is up the renderer takes
 * the smaller of the two screens' headrooms ({@link AngleRenderer}), and neither one clips.
 * Everything here runs on the EDT, where the canvas renders.
 */
public final class HdrMirror {

    // Every field below is this one object's, so nothing here is a mutable static.
    private static final HdrMirror mirror = new HdrMirror();
    private long layer;
    @Nullable private Canvas canvas;
    private boolean failed;

    /** Whether the HDR mirror is up. */
    public static boolean active() {
        return mirror.layer != 0L;
    }

    /**
     * Make the canvas's layer an EDR layer and, if its screen can show HDR now, mirror into it.
     *
     * @return whether it did; false means use the SDR mirror instead
     */
    public static boolean start(Canvas on, long metalLayer, java.awt.GraphicsDevice device) {
        if (!Display.edrCanvas)
            return false; // the main canvas holds no EDR values to show
        // macOS names a screen "Display <CGDirectDisplayID>"; anything else is not one to guess at
        String id = device.getIDstring();
        if (!id.matches("Display \\d+"))
            return false;
        double potential = MacAngleBridge.mirrorPrepare(metalLayer, (int) Long.parseLong(id.substring(8)));
        Log.info("Projector EDR potential " + potential + (potential > 1 ? ": HDR mirror" : ": SDR mirror"));
        if (potential <= 1)
            return false;
        mirror.layer = metalLayer;
        mirror.canvas = on;
        mirror.failed = false;
        DisplayController.display();
        return true;
    }

    public static void stop() {
        mirror.layer = 0L;
        mirror.canvas = null;
        DisplayController.display(); // the main screen's own headroom again
    }

    /** The projector's headroom and potential while up, for the renderer's minimum. */
    static double headroom() {
        return MacAngleBridge.mirrorHeadroom();
    }

    static double potential() {
        return MacAngleBridge.mirrorPotential();
    }

    /** After the main present: the canvas's render area, fitted to the projector. */
    static void present(long ioSurface, int width, int height) {
        Canvas c = mirror.canvas;
        if (mirror.layer == 0L || c == null || mirror.failed)
            return;
        GraphicsConfiguration gc = c.getGraphicsConfiguration();
        double sx = gc == null ? 1 : gc.getDefaultTransform().getScaleX();
        double sy = gc == null ? 1 : gc.getDefaultTransform().getScaleY();
        int dw = (int) Math.ceil(c.getWidth() * sx), dh = (int) Math.ceil(c.getHeight() * sy);
        var area = Display.fullViewport;
        if (!MacAngleBridge.mirrorPresent(mirror.layer, ioSurface, width, height, area.x, area.yGL, area.width, area.height, dw, dh)) {
            mirror.failed = true; // once: a present that failed here would fail on every frame
            Log.warn("HDR projector mirror present failed; the projector keeps its last frame");
        }
    }

    private HdrMirror() {}

}
