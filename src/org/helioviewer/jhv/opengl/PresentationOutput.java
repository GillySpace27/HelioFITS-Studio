package org.helioviewer.jhv.opengl;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.Display;
import org.helioviewer.jhv.display.DisplayController;
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
 *
 * <p>With two screens it also feeds the audience window ({@link Sink}): the canvas stays in the
 * main window on the presenter's screen, where every control works as usual, and each frame is
 * read back on the GL thread right after it is drawn and shown full screen on the projector,
 * scaled on the GPU to the projector's pixels at most (Gilly, 2026-10-06). One frame is in flight
 * at a time ({@link FrameHandoff}), so the projector runs at the rate it can paint and never ends
 * on a stale frame. The canvas is never reparented for this either (it would destroy the native
 * Metal host and every GL object).
 */
public final class PresentationOutput {

    /** The one instance; state lives in instance fields, touched only on the GL thread. */
    public static final PresentationOutput OUTPUT = new PresentationOutput();

    private volatile boolean active;
    // Gilly, 2026-10-06: draw the canvas at the Recording size outside presentation too, so a
    // small size (512) decimates the live canvas for low-latency experiments.
    private volatile boolean canvasAtRecordingSize;
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

    /** Where the audience window takes its frames. Called on the GL thread. */
    public interface Sink {
        /** Whether a frame is wanted at all: false while the window is not on screen. */
        boolean wants();

        /** The window's size in device pixels: the most a frame is read back at. */
        int[] pixels();

        /** Hands the frames over; see {@link FrameHandoff}. */
        FrameHandoff frames();

        /** A frame was published to {@link #frames()}; paint it. Any thread. */
        void framePublished();
    }

    @Nullable private volatile Sink sink;
    private int previewFbo;
    private int previewRbo;
    private int previewWidth;
    private int previewHeight;
    @Nullable private ByteBuffer previewPixels;

    private PresentationOutput() {}

    /** Set by PresentationMode while the audience window is up; null removes it. */
    public void setSink(@Nullable Sink newSink) {
        sink = newSink;
        // The first frame now, not at the next change; and on removal, the HDR gain comes back now.
        DisplayController.display();
    }

    /** Whether frames are being mirrored to the audience window, which is SDR: no HDR gain then. */
    public boolean mirroring() {
        return active && sink != null;
    }

    public boolean canvasAtRecordingSize() {
        return canvasAtRecordingSize;
    }

    /** Draw every frame at the Recording size (scaled into the render area), presenting or not. */
    public void setCanvasAtRecordingSize(boolean on) {
        canvasAtRecordingSize = on;
        DisplayController.display();
    }

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
        ViewState.Size size = renderSize(active || canvasAtRecordingSize, ViewState.recordingData().size(), areaWidth, areaHeight);
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
        presentedSource = source;
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

    private int presentedSource; // the resolved target end() last scaled to the canvas

    /**
     * Read the frame just drawn into the audience window, if one is up and has painted the last
     * frame. Runs on the GL thread at the end of display(), context current.
     *
     * @param offscreen whether this frame went through the Recording-size target; if not, the
     *                  mirror reads the canvas's render area instead
     */
    void preview(boolean offscreen) {
        Sink s = sink;
        if (s == null || !active) {
            releasePreview();
            return;
        }
        if (!s.wants())
            return;

        int source, x, y, w, h;
        if (offscreen && target != null) {
            source = presentedSource;
            x = 0;
            y = 0;
            w = targetWidth;
            h = targetHeight;
        } else {
            var area = Display.fullViewport;
            source = 0;
            x = area.x;
            y = area.yGL;
            w = area.width;
            h = area.height;
        }
        int[] box = s.pixels();
        int[] size = mirrorSize(w, h, box[0], box[1]);
        BufferedImage image = s.frames().claim(size[0], size[1]);
        if (image == null)
            return; // the last frame is still being painted; the painter asks for this one after
        try {
            ensurePreview(size[0], size[1]);
            // Scale on the GPU first, so what crosses to the CPU is never more than the projector
            // can show, and as 8-bit RGBA, the one readback format every implementation must offer.
            GL.glBindFramebuffer(GL.READ_FRAMEBUFFER, source);
            GL.glBindFramebuffer(GL.DRAW_FRAMEBUFFER, previewFbo);
            GL.glBlitFramebuffer(x, y, x + w, y + h, 0, 0, previewWidth, previewHeight, GL.COLOR_BUFFER_BIT, GL.LINEAR);
            GL.glBindFramebuffer(GL.READ_FRAMEBUFFER, previewFbo);
            GL.glPixelStorei(GL.PACK_ALIGNMENT, 1);
            ByteBuffer pixels = previewPixels;
            pixels.clear();
            GL.glReadPixels(0, 0, previewWidth, previewHeight, GL.RGBA, GL.UNSIGNED_BYTE, pixels);
        } catch (RuntimeException e) {
            Log.warn("Projector mirror unavailable", e);
            releasePreview();
            sink = null; // once: a mirror that failed here would fail on every frame
            return;
        } finally {
            GL.glBindFramebuffer(GL.FRAMEBUFFER, 0);
        }
        rgbaToRgb(previewPixels, previewWidth, previewHeight,
                ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData());
        s.frames().publish(image);
        s.framePublished();
    }

    /**
     * The size to read a frame back at: the source's shape, no larger than the box (the
     * projector's pixels), never enlarged, never below one pixel. Pure, for the check.
     */
    public static int[] mirrorSize(int sourceWidth, int sourceHeight, int boxWidth, int boxHeight) {
        int w = Math.max(1, sourceWidth), h = Math.max(1, sourceHeight);
        double scale = Math.min(1, Math.min(Math.max(1, boxWidth) / (double) w, Math.max(1, boxHeight) / (double) h));
        return new int[]{Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))};
    }

    /**
     * GL's bottom-up RGBA bytes into top-down 0xRRGGBB pixels. Pure, for the check: a swapped
     * channel or an unflipped row is invisible on a grey test frame and obvious on the Sun.
     */
    public static void rgbaToRgb(ByteBuffer rgba, int w, int h, int[] out) {
        java.nio.IntBuffer ints = rgba.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).position(0).asIntBuffer();
        for (int y = 0; y < h; y++) {
            int dst = (h - 1 - y) * w;
            ints.get(y * w, out, dst, w);
            for (int i = dst; i < dst + w; i++) {
                int p = out[i]; // little endian: 0xAABBGGRR
                out[i] = (p & 0xFF) << 16 | p & 0xFF00 | (p >>> 16) & 0xFF;
            }
        }
    }

    private void ensurePreview(int width, int height) {
        if (previewFbo != 0 && width == previewWidth && height == previewHeight)
            return;
        releasePreview();
        previewFbo = GL.glGenFramebuffer();
        GL.glBindFramebuffer(GL.FRAMEBUFFER, previewFbo);
        previewRbo = GL.glGenRenderbuffer();
        GL.glBindRenderbuffer(GL.RENDERBUFFER, previewRbo);
        GL.glRenderbufferStorage(GL.RENDERBUFFER, GL.RGBA8, width, height);
        GL.glFramebufferRenderbuffer(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.RENDERBUFFER, previewRbo);
        GL.glBindRenderbuffer(GL.RENDERBUFFER, 0);
        int status = GL.glCheckFramebufferStatus(GL.FRAMEBUFFER);
        GL.glBindFramebuffer(GL.FRAMEBUFFER, 0);
        if (status != GL.FRAMEBUFFER_COMPLETE) {
            releasePreview();
            throw new GLException("Presenter preview framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
        previewWidth = width;
        previewHeight = height;
        previewPixels = ByteBuffer.allocateDirect(width * height * 4);
    }

    private void releasePreview() {
        if (previewRbo != 0)
            GL.glDeleteRenderbuffer(previewRbo);
        if (previewFbo != 0)
            GL.glDeleteFramebuffer(previewFbo);
        previewFbo = previewRbo = 0;
        previewWidth = previewHeight = 0;
        previewPixels = null;
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
