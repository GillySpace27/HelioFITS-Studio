package org.helioviewer.jhv.layers;

import java.lang.reflect.Constructor;

import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.opengl.BufVertex;

/**
 * A comet shown in the 3D scene keeps its comet glyph. Identify draws the glyph only beside its own
 * rings; with "Show in 3D scene" on, the comet layer draws the rings and Identify stands back, so
 * the glyph vanished (Gilly, 2026-11-22). The layer now draws the same glyph inside each ring.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.CometLayerGlyphCheck
 */
public final class CometLayerGlyphCheck {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-glyph").toString()); // never the real settings
        // CometLayer's class reaches the layer registry, whose null image layer reaches SPICE (as ScenePlanetsRestoreCheck)
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        Constructor<Viewport> make = Viewport.class.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class, int.class);
        make.setAccessible(true); // package-private; a 400 x 300 canvas at the window's origin
        Viewport vp = make.newInstance(0, 0, 0, 400, 300, 300);
        byte[] col = {(byte) 255, (byte) 200, 0, (byte) 255};
        double[] c = {200, 100}; // page point, y down

        BufVertex plain = new BufVertex();
        int ringOnly = CometLayer.putMarker(plain, c, vp, 12, 1, false, col);
        BufVertex withGlyph = new BufVertex();
        int marked = CometLayer.putMarker(withGlyph, c, vp, 12, 1, true, col);
        check(ringOnly == 1, "glyph off: the ring alone (" + ringOnly + " stroke)");
        check(marked == 5, "glyph on: the ring, the head and three tails (" + marked + " strokes)");
        check(withGlyph.getCount() > plain.getCount(), "and the glyph's vertices are in the buffer (" + plain.getCount() + " -> " + withGlyph.getCount() + ')');

        System.out.println(failures == 0 ? "CometLayerGlyphCheck: PASS" : "CometLayerGlyphCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private CometLayerGlyphCheck() {}
}
