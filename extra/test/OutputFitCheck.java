package org.helioviewer.jhv.display;

/**
 * The letterboxed render area: the inset that makes the on-screen view and the recorded frame
 * the same picture.
 *
 * <p>Two invariants matter here, and they pull in opposite directions.
 *
 * <p><b>The window must not reach the video.</b> Resolution comes from the aspect plus the long
 * side, and framing from the camera plus the aspect; the window only decides how big the
 * preview is. So the render area's SHAPE must depend on the output aspect alone, and never on
 * the canvas -- the canvas only decides its size. A regression here is invisible on screen and
 * shows up as recordings that differ between machines.
 *
 * <p><b>The bars must never reach the file.</b> GLGrab renders into a target that already IS
 * the output size, so it suppresses the inset; if that ever stopped working, every recording
 * would gain baked-in letterbox bars and nothing on screen would say so.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.OutputFitCheck
 */
public final class OutputFitCheck {

    private static int failures;

    public static void main(String[] args) {
        double savedAspect = Display.getOutputAspect();
        boolean savedSuppressed = Display.outputFitSuppressed;
        try {
            // "On screen": no aspect, so the render area is the whole canvas.
            Display.outputFitSuppressed = false;
            Display.setOutputAspect(0);
            Display.setGLSize(0, 0, 1600, 1000);
            area(1600, 1000, 0, 0, "no fixed aspect uses the whole canvas");

            // Output wider than the canvas: width binds, bars top and bottom.
            Display.setOutputAspect(2.0);
            Display.setGLSize(0, 0, 1600, 1000);
            area(1600, 800, 0, 100, "2:1 in a 1.6 canvas is width-bound");

            // Output narrower than the canvas: height binds, bars left and right. This is the
            // case a camera-zoom letterbox cannot express at all -- it can shrink the scene but
            // not mask the sides -- which is why the inset lives in the viewport.
            Display.setOutputAspect(1.0);
            Display.setGLSize(0, 0, 1600, 1000);
            area(1000, 1000, 300, 0, "1:1 in a wide canvas is height-bound");

            // Same shape whatever the canvas is: only the size follows the window.
            Display.setOutputAspect(16 / 9.);
            for (int[] canvas : new int[][]{{1600, 1000}, {800, 500}, {1000, 1600}, {3840, 1080}}) {
                Display.setGLSize(0, 0, canvas[0], canvas[1]);
                double shape = Display.fullViewport.width / (double) Display.fullViewport.height;
                near(shape, 16 / 9., 2e-3, "render area is 16:9 in a " + canvas[0] + "x" + canvas[1] + " canvas");
                expect(Display.fullViewport.width <= canvas[0] && Display.fullViewport.height <= canvas[1],
                        "render area fits inside the " + canvas[0] + "x" + canvas[1] + " canvas");
            }

            // The GL viewport's y is measured from the bottom of the DRAWABLE, not of the render
            // area, or the inset scene would be drawn off the bottom of the window.
            Display.setOutputAspect(2.0);
            Display.setGLSize(0, 0, 1600, 1000);
            same(Display.fullViewport.yGL, 100, "GL y is measured against the canvas, not the render area");
            same(Display.fullViewport.yAWT, 100, "AWT y is the top inset");

            // Anything positioned "in the corner of the view" must mean the render area's
            // corner, and must still measure GL y against the drawable. Getting either half
            // wrong is invisible without a fixed aspect, because the two coincide there. The
            // miniview had both wrong: it sat in the canvas corner, i.e. inside the bar and
            // outside the recorded frame, and was displaced vertically by the bar's height.
            Display.setOutputAspect(2.0);
            Display.setGLSize(0, 0, 1600, 1000);
            Viewport render = Display.fullViewport;
            org.helioviewer.jhv.layers.MiniviewLayer mini = new org.helioviewer.jhv.layers.MiniviewLayer(null);
            Viewport mv = mini.getViewport();
            expect(mv.x >= render.x && mv.x + mv.width <= render.x + render.width,
                    "miniview sits inside the render area horizontally");
            expect(mv.yAWT >= render.yAWT && mv.yAWT + mv.height <= render.yAWT + render.height,
                    "miniview sits inside the render area vertically");
            same(mv.yGL, Display.getCanvasHeight() - mv.height - mv.yAWT,
                    "miniview GL y is measured against the drawable");

            // Capture: suppressed, so the target is used whole and the video carries no bars.
            Display.outputFitSuppressed = true;
            for (double ratio : new double[]{2.0, 16 / 9., 1.0}) {
                Display.setOutputAspect(ratio);
                Display.setGLSize(0, 0, 1920, 1080);
                area(1920, 1080, 0, 0, "capture at ratio " + ratio + " is never inset");
            }
        } finally {
            Display.outputFitSuppressed = savedSuppressed;
            Display.setOutputAspect(savedAspect);
        }

        System.out.println(failures == 0 ? "OutputFitCheck: PASS" : "OutputFitCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    private static void area(int w, int h, int x, int y, String what) {
        Viewport vp = Display.fullViewport;
        if (vp.width != w || vp.height != h || vp.x != x || vp.yAWT != y) {
            System.out.printf("FAIL: %s -- got %dx%d at (%d,%d), want %dx%d at (%d,%d)%n",
                    what, vp.width, vp.height, vp.x, vp.yAWT, w, h, x, y);
            failures++;
        }
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private static void same(int got, int want, String what) {
        if (got != want) {
            System.out.println("FAIL: " + what + " -- got " + got + ", want " + want);
            failures++;
        }
    }

    private static void near(double got, double want, double tol, String what) {
        if (Double.isNaN(got) || Math.abs(got - want) > tol * Math.max(1, Math.abs(want))) {
            System.out.printf("FAIL: %s -- got %.12f, want %.12f%n", what, got, want);
            failures++;
        }
    }

    private OutputFitCheck() {}
}
