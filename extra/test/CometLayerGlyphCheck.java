package org.helioviewer.jhv.layers;

import java.lang.reflect.Constructor;

import org.helioviewer.jhv.display.Viewport;
import org.helioviewer.jhv.identify.IdentifySettings;
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
        org.helioviewer.jhv.io.Directories.createPersistentDirs(); // the settings file the glyph switch is saved in
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

        // The glyph sits on its ring: the ring's centre in GL pixels is (200, 300 - 100)
        java.nio.ByteBuffer bytes = withGlyph.toBuffer().duplicate().order(java.nio.ByteOrder.nativeOrder());
        double far = 0;
        for (int i = plain.getCount(); i < withGlyph.getCount(); i++) {
            int at = i * 20; // four floats of position, four bytes of colour
            far = Math.max(far, Math.hypot(bytes.getFloat(at) - 200, bytes.getFloat(at + 4) - 200));
        }
        check(far > 0 && far <= 3 * 12, "the glyph is drawn on its ring, within 3 ring radii (" + Math.round(far) + " px)");

        // What renderFloat draws with, from the settings
        int[] rgb = {255, 200, 0};
        CometLayer.RingStyle s = CometLayer.ringStyle(rgb, 1, 1);
        check(s.glyph(), "the glyph setting on (its default) reaches the rings");
        check(s.widthPx() == CometLayer.ringWidthPx(1, 1) && s.glyphScale() == 1, "default width and glyph scale");
        CometLayer.RingStyle export = CometLayer.ringStyle(rgb, 1, 2);
        check(export.glyphScale() == 2 && export.widthPx() == 2 * s.widthPx(), "an export at capture scale 2 doubles the glyph with the ring (" + export.glyphScale() + ')');
        IdentifySettings.set(IdentifySettings.COMET_GLYPH, "false");
        check(!CometLayer.ringStyle(rgb, 1, 1).glyph(), "the glyph setting off reaches the rings");
        IdentifySettings.set(IdentifySettings.COMET_GLYPH, "true");
        IdentifySettings.preview(IdentifySettings.BOLD_WIDTH, "2");
        IdentifySettings.preview(IdentifySettings.BOLD_SHADOW, "1");
        CometLayer.RingStyle bold = CometLayer.ringStyle(rgb, 1, 1);
        check(bold.widthPx() == 2 * s.widthPx(), "Boldness width 2 doubles the ring's line (" + bold.widthPx() + ')');
        check((bold.shadow()[3] & 255) == 200 && bold.shadowWidthPx() > bold.widthPx(), "Boldness shadow 1 draws a wider dark outline");
        check(s.shadow()[3] == 0, "and none by default");

        System.out.println(failures == 0 ? "CometLayerGlyphCheck: PASS" : "CometLayerGlyphCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private CometLayerGlyphCheck() {}
}
