package org.helioviewer.jhv.opengl;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.MiniviewLayer;

/**
 * Presentation mode's picture at the Recording pixel size, scaled to the projector.
 *
 * <p>Gilly's call, 2026-09-30, after the dome test at Fiske: while presenting, the output
 * follows Playback and Recording. The aspect already did, because {@link Display#setGLSize}
 * insets the render area to it on screen; the long side only ever reached a recording. So while
 * presenting with a fixed Recording aspect, each frame is drawn offscreen at exactly the size a
 * recording would be written at, the same way {@link GLGrab} draws one, and then scaled into the
 * render area on the projector. What the room sees is what Record would write, at whatever size
 * the projector happens to be.
 *
 * <p>The canvas is untouched: no reparenting, no new surface. Only the framebuffer the frame is
 * drawn into changes, and the screen's layout is put back after every frame, so mouse handling
 * between frames sees the window as it is. With "On screen" there is no Recording size to
 * follow and frames go straight to the canvas as before.
 */
public final class PresentationOutput {

    /** The one instance; state lives in instance fields, touched only on the GL thread. */
    public static final PresentationOutput OUTPUT = new PresentationOutput();

    private volatile boolean active;
    @Nullable private GLFrameCapture target;
    private int targetWidth;
    private int targetHeight;
    private boolean highBitDepth;
    private int failedWidth = -1;
    private int failedHeight = -1;

    // What begin() changed, for end() to put back.
    @Nullable private Display.Layout saved;
    private double savedCaptureScale;
    private boolean savedFitSuppressed;
    private boolean drawing;

    private PresentationOutput() {}

    /** Set by PresentationMode on entering and leaving the mode. */
    public void setActive(boolean on) {
        active = on;
    }

    /**
     * The size to draw a presented frame at, or null to draw straight into the canvas.
     *
     * <p>Pure, so the check can pin it without a GL context. Null when not presenting, when the
     * Recording aspect is "On screen" (its size is the render area itself, so there is nothing
     * to follow), and when the Recording size already equals the render area (scaling by one is
     * a copy that buys nothing).
     */
    @Nullable
    public static ViewState.Size renderSize(boolean presenting, ViewState.Size recording, int areaWidth, int areaHeight) {
        if (!presenting || !recording.internal() || recording.width() <= 0 || recording.height() <= 0)
            return null;
        if (recording.width() == areaWidth && recording.height() == areaHeight)
            return null;
        return recording;
    }

    /**
     * How much larger the offscreen render area is than the on-screen one, by height: what
     * {@link Display#captureScale} becomes, so labels sized in screen pixels keep their share of
     * the frame, exactly as in a recording of the same size.
     */
    public static double captureScale(int renderHeight, int screenRenderHeight) {
        return renderHeight / (double) Math.max(1, screenRenderHeight);
    }

    /** The framebuffer this frame draws into: 0 (the canvas) unless presenting at Recording size. */
    int framebuffer() {
        return drawing && target != null ? target.drawFramebuffer() : 0;
    }

    /**
     * Point the frame at the Recording-size target, if this frame should use one.
     *
     * @return whether it did; when true, {@link #end} must follow once the scene is drawn
     */
    boolean begin() {
        int areaWidth = Display.fullViewport.width;
        int areaHeight = Display.fullViewport.height;
        ViewState.Size size = renderSize(active, ViewState.recordingData().size(), areaWidth, areaHeight);
        if (size == null) {
            release();
            return false;
        }
        if (!ensureTarget(size.width(), size.height()))
            return false;

        saved = Display.saveLayout();
        savedCaptureScale = Display.captureScale;
        savedFitSuppressed = Display.outputFitSuppressed;

        // The target IS the output's shape, so no bars inside it; they stay on the canvas.
        Display.outputFitSuppressed = true;
        Display.setGLSize(0, 0, size.width(), size.height());
        Display.reshapeAll();
        Display.captureScale = captureScale(Display.fullViewport.height, areaHeight);
        MiniviewLayer miniview = Layers.getMiniviewLayer();
        if (miniview != null)
            miniview.reshapeViewport();

        drawing = true;
        target.bindForRender();
        return true;
    }

    /** Put the screen's layout back and scale the frame into the canvas's render area. */
    void end() {
        drawing = false;
        Display.captureScale = savedCaptureScale;
        Display.outputFitSuppressed = savedFitSuppressed;
        if (saved != null)
            Display.restoreLayout(saved);
        saved = null;
        MiniviewLayer miniview = Layers.getMiniviewLayer();
        if (miniview != null)
            miniview.reshapeViewport();

        GLFrameCapture t = target;
        if (t == null) {
            GL.glBindFramebuffer(GL.FRAMEBUFFER, 0);
            return;
        }
        int source = t.resolve();
        GL.glBindFramebuffer(GL.FRAMEBUFFER, 0);
        // The bars around the render area, in the clear colour display() chose.
        GL.glViewport(0, 0, Display.getCanvasWidth(), Display.getCanvasHeight());
        GL.glClear(GL.COLOR_BUFFER_BIT | GL.DEPTH_BUFFER_BIT);

        var area = Display.fullViewport;
        GL.glBindFramebuffer(GL.READ_FRAMEBUFFER, source);
        GL.glBindFramebuffer(GL.DRAW_FRAMEBUFFER, 0);
        GL.glBlitFramebuffer(0, 0, targetWidth, targetHeight,
                area.x, area.yGL, area.x + area.width, area.yGL + area.height,
                GL.COLOR_BUFFER_BIT, GL.LINEAR);
        GL.glBindFramebuffer(GL.FRAMEBUFFER, 0);
    }

    private boolean ensureTarget(int width, int height) {
        // Deep and EDR canvases hold values a plain 8-bit target would clip or band.
        boolean wantHigh = Display.deepCanvas || Display.edrCanvas;
        if (target != null && width == targetWidth && height == targetHeight && wantHigh == highBitDepth)
            return true;
        release();
        if (width == failedWidth && height == failedHeight)
            return false; // already refused at this size; asking every frame would only log every frame
        try {
            target = create(width, height, wantHigh);
            targetWidth = width;
            targetHeight = height;
            highBitDepth = wantHigh;
            failedWidth = failedHeight = -1;
            return true;
        } catch (RuntimeException e) {
            Log.warn("Presentation at the Recording size " + width + "x" + height
                    + " is unavailable; presenting at the projector's size instead", e);
            failedWidth = width;
            failedHeight = height;
            return false;
        }
    }

    private static GLFrameCapture create(int width, int height, boolean wantHigh) {
        if (wantHigh) {
            try {
                return new GLFrameCapture(width, height, true, 0);
            } catch (RuntimeException e) {
                Log.warn("High-bit-depth presentation target unavailable, using 8 bits: " + e.getMessage());
            }
        }
        return new GLFrameCapture(width, height, false, 0);
    }

    // Called on the GL thread only (from begin), where the context is current.
    private void release() {
        if (target != null) {
            target.dispose();
            target = null;
        }
        targetWidth = targetHeight = 0;
    }

}
