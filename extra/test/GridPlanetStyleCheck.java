package org.helioviewer.jhv.layers;

import org.helioviewer.jhv.base.Colors;
import org.json.JSONObject;

/**
 * The planet markers' colour and size (Gilly, 2026-11-19): each planet keeps its own colour and the
 * default size until told otherwise, a chosen colour and size survive a session round trip, a session
 * written before these keys existed reads as the defaults, and the size stays in its 0.25x to 4x range.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.GridPlanetStyleCheck
 */
public final class GridPlanetStyleCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-planet-style").toString()); // never the real settings
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        GridLayer fresh = new GridLayer(new JSONObject());
        expect("a new grid keeps each planet's own colour", fresh.getPlanetColor() == null);
        expect("and the default marker size", fresh.getPlanetScale() == 1);

        GridLayer old = new GridLayer(new JSONObject().put("showPlanets", true)); // a session from before these keys
        expect("an older session reads as each planet's own colour", old.getPlanetColor() == null);
        expect("and the default marker size", old.getPlanetScale() == 1);

        fresh.setPlanetColor(Colors.Orange);
        fresh.setPlanetScale(2.5);
        JSONObject jo = new JSONObject();
        fresh.serialize(jo);
        expect("the colour is stored by name (got " + jo.opt("planetColor") + ")", "Orange".equals(jo.optString("planetColor")));
        GridLayer back = new GridLayer(jo);
        expect("and comes back", back.getPlanetColor() == Colors.Orange);
        expect("the size comes back (got " + back.getPlanetScale() + ")", back.getPlanetScale() == 2.5);

        fresh.setPlanetColor(null);
        jo = new JSONObject();
        fresh.serialize(jo);
        expect("each planet's own colour round-trips", new GridLayer(jo).getPlanetColor() == null);

        fresh.setPlanetScale(40);
        expect("the size is held to 4x at most", fresh.getPlanetScale() == 4);
        expect("and a stored 0.01 reads as 0.25x", new GridLayer(new JSONObject().put("planetScale", 0.01)).getPlanetScale() == 0.25);

        System.out.println(failures == 0 ? "GridPlanetStyleCheck: PASS" : "GridPlanetStyleCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private GridPlanetStyleCheck() {}
}
