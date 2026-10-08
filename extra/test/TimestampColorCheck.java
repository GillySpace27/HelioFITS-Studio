package org.helioviewer.jhv.layers;

import java.awt.Color;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.io.Directories;

import org.json.JSONObject;

/**
 * The timestamp overlay's text colour (PUNCH team, 2026-10-07): chosen in the layer's options,
 * kept in the session, and absent from the session when nobody chose one, so a default session
 * reads exactly as before in an older build. A colour that does not parse keeps the default
 * rather than failing the load.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.TimestampColorCheck
 */
public final class TimestampColorCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static JSONObject saved(TimestampLayer layer) {
        JSONObject jo = new JSONObject();
        layer.serialize(jo);
        return jo;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-timestamp-color").toString());
        Platform.init();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs();

        TimestampLayer fresh = new TimestampLayer(null);
        expect("a fresh layer has no chosen colour", fresh.getColor() == null);
        expect("and writes no color key, got " + saved(fresh), !saved(fresh).has("color"));

        fresh.applyColor(new Color(255, 200, 0));
        JSONObject jo = saved(fresh);
        expect("a chosen colour is saved as hex, got " + jo.opt("color"), "#ffc800".equals(jo.opt("color")));
        TimestampLayer restored = new TimestampLayer(jo);
        expect("and restores to the same colour, got " + restored.getColor(), new Color(255, 200, 0).equals(restored.getColor()));

        fresh.applyColor(new Color(10, 20, 30, 40));
        expect("the chosen colour is opaque, the shadow carries the contrast", fresh.getColor().getAlpha() == 255);

        fresh.applyColor(null);
        expect("Default clears the choice and the key", fresh.getColor() == null && !saved(fresh).has("color"));

        TimestampLayer bad = new TimestampLayer(new JSONObject().put("color", "#zzzzzz"));
        expect("an unreadable colour keeps the default", bad.getColor() == null);
        TimestampLayer old = new TimestampLayer(new JSONObject().put("scale", 120));
        expect("a session from before the colour keeps the default", old.getColor() == null && old.getScale() == 120);

        System.out.println(failures == 0 ? "TimestampColorCheck: all ok" : "TimestampColorCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

}
