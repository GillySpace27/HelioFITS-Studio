package org.helioviewer.jhv.layers;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.io.Directories;

import org.json.JSONObject;

/**
 * Two items from Sarah Gibson's report (2026-10-10). The timestamp can carry the image layer's
 * name in single view too ("Show layer name"): on for a fresh layer, off for a session saved
 * before the option, so an old session draws as it did. And the timestamp block moves out from
 * under the miniview when the two would overlap (TimestampLayer.clearOf).
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.TimestampNameCheck
 */
public final class TimestampNameCheck {

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

    private static String at(int[] p) {
        return p[0] + "," + p[1];
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-timestamp-name").toString());
        Platform.init();
        Directories.createCacheDirs();
        Directories.SETTINGS.getFile().mkdirs();

        TimestampLayer fresh = new TimestampLayer(null);
        expect("a fresh layer shows the layer name", fresh.isShowName());
        expect("and saves showName true, got " + saved(fresh), saved(fresh).optBoolean("showName", false));

        TimestampLayer old = new TimestampLayer(new JSONObject().put("scale", 120).put("extra", true));
        expect("a session without the key keeps the old look (no name)", !old.isShowName());

        TimestampLayer off = new TimestampLayer(new JSONObject().put("showName", false));
        expect("showName false restores false", !off.isShowName());
        expect("and saves false again, got " + saved(off), saved(off).has("showName") && !saved(off).getBoolean("showName"));
        TimestampLayer on = new TimestampLayer(saved(fresh));
        expect("showName true round-trips", on.isShowName());

        // A 1000 x 600 viewport, margin 6; the miniview a 100 x 100 box in the top-left corner
        // (GL y up: x 10..110, y 490..590). The timestamp block is 400 x 20 at the top-left.
        int vpW = 1000, margin = 6;
        int[] p = TimestampLayer.clearOf(6, 574, 400, 20, 10, 490, 110, 590, vpW, margin);
        expect("an overlapping block moves right of the miniview, got " + at(p), p[0] == 116 && p[1] == 574);

        p = TimestampLayer.clearOf(6, 6, 400, 20, 10, 490, 110, 590, vpW, margin);
        expect("a block clear of the miniview stays put, got " + at(p), p[0] == 6 && p[1] == 6);

        p = TimestampLayer.clearOf(6, 574, 900, 20, 10, 490, 110, 590, vpW, margin);
        expect("too wide for the right side, it moves below the miniview, got " + at(p), p[0] == 6 && p[1] == 464);

        p = TimestampLayer.clearOf(6, 574, 900, 500, 10, 490, 110, 590, vpW, margin);
        expect("fits nowhere, it stays where it was, got " + at(p), p[0] == 6 && p[1] == 574);

        p = TimestampLayer.clearOf(6, 574, 400, 20, 0, 0, 0, 0, vpW, margin);
        expect("an empty footprint (no miniview here) moves nothing, got " + at(p), p[0] == 6 && p[1] == 574);

        System.out.println(failures == 0 ? "TimestampNameCheck: all ok" : "TimestampNameCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

}
