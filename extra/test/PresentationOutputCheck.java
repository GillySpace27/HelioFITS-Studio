package org.helioviewer.jhv.opengl;

import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.Display;

/**
 * Presentation mode follows Playback and Recording: the frame is drawn at the Recording pixel
 * size and scaled to the projector (Gilly, 2026-09-30, after the Fiske dome test).
 *
 * <p>What is pinned here is the decision and the bookkeeping, which is everything that can go
 * wrong without a projector: when a frame goes offscreen at all, at what size, how labels scale,
 * and that the screen's layout comes back exactly after the pass, active viewport included, so
 * mouse handling between frames never sees the Recording-size layout. The blit itself needs a
 * GL context and a screen and is not checked here.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:lib/*" org.helioviewer.jhv.opengl.PresentationOutputCheck
 */
public final class PresentationOutputCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static boolean is(ViewState.Size s, int w, int h) {
        return s != null && s.width() == w && s.height() == h;
    }

    public static void main(String[] args) {
        ViewState.Size square2048 = ViewState.RecordingAspect.SQUARE.sizeFor(2048);
        ViewState.Size wide3840 = ViewState.RecordingAspect.WIDE.sizeFor(3840);
        ViewState.Size wide1280 = ViewState.RecordingAspect.WIDE.sizeFor(1280);
        ViewState.Size onScreen = new ViewState.Size(1920, 1080, false); // what "On screen" yields

        expect("not presenting: frames go straight to the canvas",
                PresentationOutput.renderSize(false, square2048, 1080, 1080) == null);
        expect("presenting 1:1 at 2048 on a 1080-high projector renders at 2048x2048",
                is(PresentationOutput.renderSize(true, square2048, 1080, 1080), 2048, 2048));
        expect("presenting 16:9 at 3840 on a 1920x1080 projector renders at 3840x2160",
                is(PresentationOutput.renderSize(true, wide3840, 1920, 1080), 3840, 2160));
        expect("a Recording size below the projector's is still followed (rendered small, scaled up)",
                is(PresentationOutput.renderSize(true, wide1280, 1920, 1080), 1280, 720));
        // Its size is read from the render area, so normally the two agree; a stale one (the window
        // moved to the projector since) must still not be followed.
        expect("\"On screen\" has no Recording size to follow, even a stale one",
                PresentationOutput.renderSize(true, onScreen, 1280, 720) == null);
        expect("a Recording size equal to the render area needs no offscreen pass",
                PresentationOutput.renderSize(true, wide3840, 3840, 2160) == null);
        expect("a degenerate size is refused rather than drawn into",
                PresentationOutput.renderSize(true, new ViewState.Size(0, 0, true), 1920, 1080) == null);

        double scale = PresentationOutput.captureScale(2048, 1080);
        expect("labels scale by the render heights' ratio (2048 over 1080), as in a recording: " + scale,
                Math.abs(scale - 2048 / 1080.) < 1e-12);
        expect("a zero screen height cannot divide by zero",
                Double.isFinite(PresentationOutput.captureScale(2048, 0)));

        // The layout round trip begin() and end() rely on.
        double savedAspect = Display.getOutputAspect();
        boolean savedSuppressed = Display.outputFitSuppressed;
        try {
            Display.outputFitSuppressed = false;
            Display.setOutputAspect(1.0);
            Display.setGLSize(0, 0, 1920, 1080);
            Display.reshapeAll();
            Display.Layout screen = Display.saveLayout();
            var screenFull = Display.fullViewport;
            var screenViewports = Display.getViewports();

            Display.outputFitSuppressed = true;
            Display.setGLSize(0, 0, 2048, 2048);
            Display.reshapeAll();
            expect("during the pass the render area is the Recording size",
                    Display.fullViewport.width == 2048 && Display.fullViewport.height == 2048);
            Display.outputFitSuppressed = false;

            Display.restoreLayout(screen);
            expect("afterwards the canvas is the screen's again",
                    Display.getCanvasWidth() == 1920 && Display.getCanvasHeight() == 1080);
            expect("and so is the render area, letterboxed as before",
                    Display.fullViewport == screenFull && screenFull.width == 1080 && screenFull.x == 420);
            expect("and the viewports are the very ones the screen had, zoom and all",
                    Display.getViewports() == screenViewports);
        } finally {
            Display.outputFitSuppressed = savedSuppressed;
            Display.setOutputAspect(savedAspect);
        }

        if (failures > 0) {
            System.out.println("PresentationOutputCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("PresentationOutputCheck: all passed");
    }

}
