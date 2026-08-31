package org.helioviewer.jhv.opengl;

import java.nio.ByteBuffer;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.display.Display;

public class GLGrab {

    public final int w;
    public final int h;
    private final boolean wantHighBitDepth;
    private GLFrameCapture capture;

    public GLGrab(int _w, int _h, boolean _wantHighBitDepth) {
        w = _w;
        h = _h;
        wantHighBitDepth = _wantHighBitDepth;
    }

    /**
     * Bytes per pixel the capture will produce: 3 (rgb24) or 6 (rgb48le). Only valid once a
     * frame has been rendered, since the target is built lazily and may have fallen back.
     */
    public int bytesPerPixel() {
        return capture == null ? (wantHighBitDepth ? 6 : 3) : capture.bytesPerPixel();
    }

    private void init() {
        if (wantHighBitDepth) {
            // Optimistic: RGBA16F is colour-renderable only with EXT_color_buffer_half_float,
            // and a driver without it should cost the recording its extra bits, not the
            // recording itself.
            try {
                capture = new GLFrameCapture(w, h, true);
                return;
            } catch (RuntimeException e) {
                Log.warn("High-bit-depth capture unavailable, falling back to 8-bit: " + e.getMessage());
            }
        }
        capture = new GLFrameCapture(w, h, false);
    }

    public void dispose() {
        if (capture != null) {
            capture.dispose();
            capture = null;
        }
    }

    public void renderFrame(ByteBuffer buffer) {
        if (capture == null)
            init();

        // The canvas, not fullViewport: with a fixed output aspect the viewport is the inset
        // render area, so restoring from it would shrink the drawable a little more on every
        // capture. The canvas is the drawable itself and is what setGLSize wants back.
        int _w = Display.getCanvasWidth();
        int _h = Display.getCanvasHeight();
        // The bars exist to reconcile a window whose shape differs from the output's. Here the
        // target IS the output, so insetting would letterbox the written video itself.
        boolean _suppressed = Display.outputFitSuppressed;

        boolean _high = Display.highBitDepthCapture;

        try {
            Display.highBitDepthCapture = capture.bytesPerPixel() > 3;
            Display.outputFitSuppressed = true;
            Display.setGLSize(0, 0, w, h);
            Display.reshapeAll();

            capture.bindForRender();
            GL.glClear(GL.COLOR_BUFFER_BIT | GL.DEPTH_BUFFER_BIT);
            if (GLRenderer.getMapView().rendersIn3D()) { // must match display()'s fork, or export takes the wrong path
                GLRenderer.renderScene();
            } else {
                GLRenderer.renderSceneScale();
            }
            capture.readPixels(buffer);
        } finally {
            Display.highBitDepthCapture = _high;
            Display.outputFitSuppressed = _suppressed;
            Display.setGLSize(0, 0, _w, _h);
            Display.reshapeAll();
        }
    }

}
