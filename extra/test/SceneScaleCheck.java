package org.helioviewer.jhv.layers;

import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONObject;

/**
 * The 3D Scene pane's scene-wide sizes (SceneScale) and how a model layer's own sizes combine with
 * them (ModelPlacement.drawnScale). Pins: the built-in scene sizes draw exactly as before; the
 * scene's Enlarge multiplies a layer's and the product is capped; the scene's Min size scales a
 * layer's floor; a session saved before the pane opens at the built-in sizes whatever the
 * defaults; the sizes survive the session JSON and are clamped. Offline; Settings in a temp home.
 */
public final class SceneScaleCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("scene-scale-check");
        System.setProperty("user.home", home.toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice(); // a size change asks for a redraw, which reads Earth's position

        ModelPlacement m = new ModelPlacement();
        double w = 430, h = 1000, extent = 10; // a 10 m model in a 430 Rsun wide, 1000 px high view

        m.setMinPixels(0);
        expect(m.drawnScale(extent, w, h, 1, 24) == m.trueScale(), "at the built-in scene sizes with no floor, true size");
        m.setExaggeration(1e7);
        expect(close(m.drawnScale(extent, w, h, 100, 24) / m.trueScale(), 1e9), "the scene's Enlarge multiplies the layer's (1e7 x 100 = 1e9)");
        expect(close(m.drawnScale(extent, w, h, 1e6, 24) / m.trueScale(), 1e10), "and the product is capped at 1e10");

        m.setExaggeration(1);
        m.setMinPixels(24);
        expect(close(px(m.drawnScale(extent, w, h, 1, 24), extent, w, h), 24), "a layer at 1x the scene's Min size gets the scene's 24 px");
        expect(close(px(m.drawnScale(extent, w, h, 1, 48), extent, w, h), 48), "raising the scene's Min size to 48 px raises it");
        m.setMinPixels(48);
        expect(close(px(m.drawnScale(extent, w, h, 1, 10), extent, w, h), 20), "a layer at 2x a 10 px scene floor gets 20 px");
        m.setMinPixels(0);
        expect(m.drawnScale(extent, w, h, 1, 50) == m.trueScale(), "a layer's floor of 0 stays off whatever the scene's");

        SceneScale.setDefaultEnlarge(1000);
        SceneScale.setDefaultMinPixels(40);
        expect(SceneScale.defaultEnlarge() == 1000 && SceneScale.defaultMinPixels() == 40, "the new-session defaults are kept");
        SceneScale.fromJson(null);
        expect(SceneScale.enlarge() == SceneScale.BUILT_IN_ENLARGE && SceneScale.minPixels() == SceneScale.BUILT_IN_MIN_PIXELS,
                "a session from before the pane opens at 1x and 24 px, not the defaults (got " + SceneScale.enlarge() + ", " + SceneScale.minPixels() + ")");

        SceneScale.setEnlarge(3e5);
        SceneScale.setMinPixels(12);
        JSONObject saved = new JSONObject(SceneScale.toJson().toString());
        SceneScale.fromJson(null);
        SceneScale.fromJson(saved);
        expect(SceneScale.enlarge() == 3e5 && SceneScale.minPixels() == 12, "the sizes survive the session JSON");

        SceneScale.fromJson(new JSONObject().put("enlarge", 0.5).put("minPixels", 80));
        expect(SceneScale.enlarge() == 1 && SceneScale.minPixels() == SceneScale.MAX_MIN_PIXELS, "out-of-range sizes are clamped to 1x and 50 px");

        if (failures != 0) {
            System.out.println("SceneScaleCheck: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("SceneScaleCheck: PASS");
        System.exit(0);
    }

    private static double px(double scale, double extent, double w, double h) {
        return extent * scale * h / w;
    }

    private static boolean close(double a, double b) {
        return Math.abs(a / b - 1) < 1e-9;
    }

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

}
