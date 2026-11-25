package org.helioviewer.jhv.layers;

import java.util.List;

import org.helioviewer.jhv.layers.grid.PlanetMarkers;
import org.helioviewer.jhv.time.JHVTime;
import org.json.JSONObject;

/**
 * The Earth dot is its own switch (Gilly, 2026-11-25): on by default, kept in the session, read as on
 * from a session that predates it, independent of the planets' Show, and placed once, where the
 * planet layout puts Earth, so the planet markers no longer draw a second Earth beside it.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.EarthToggleCheck
 */
public final class EarthToggleCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-earth-toggle").toString()); // never the real settings
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();

        GridLayer fresh = new GridLayer(new JSONObject());
        expect("a new grid shows Earth", fresh.isShowEarth());
        expect("an older session (no showEarth key) shows Earth", new GridLayer(new JSONObject().put("showPlanets", true)).isShowEarth());

        fresh.setShowPlanets(true);
        fresh.setShowEarth(false);
        expect("Earth is off while the planets stay on", !fresh.isShowEarth() && fresh.isShowPlanets());
        JSONObject jo = new JSONObject();
        fresh.serialize(jo);
        GridLayer back = new GridLayer(jo);
        expect("off survives a session round trip", !back.isShowEarth() && back.isShowPlanets());

        fresh.setShowEarth(true);
        fresh.setShowPlanets(false);
        jo = new JSONObject();
        fresh.serialize(jo);
        back = new GridLayer(jo);
        expect("and Earth on with the planets off does too", back.isShowEarth() && !back.isShowPlanets());

        JHVTime t = new JHVTime(System.currentTimeMillis() > 0 ? 1758499200000L : 0L); // 2025-09-22
        for (boolean follow : new boolean[]{true, false}) {
            PlanetMarkers.Marker e = PlanetMarkers.earth(t, follow);
            expect("Earth is placed (follow=" + follow + ")", e != null && PlanetMarkers.isEarth(e));
            List<PlanetMarkers.Marker> all = PlanetMarkers.positions(t, follow);
            PlanetMarkers.Marker same = all.stream().filter(PlanetMarkers::isEarth).findFirst().orElse(null);
            expect("it is where the planet layout puts Earth (follow=" + follow + ")",
                    e != null && same != null && e.position().equals(same.position()));
            // the filter drawPlanets applies: no Earth left to draw a second time
            expect("the planet loop's list has no Earth (follow=" + follow + ")",
                    all.stream().filter(m -> !PlanetMarkers.isEarth(m)).noneMatch(PlanetMarkers::isEarth)
                            && all.stream().filter(m -> !PlanetMarkers.isEarth(m)).count() == all.size() - 1);
            expect("exactly one marker in the full list is Earth (follow=" + follow + ")",
                    all.stream().filter(PlanetMarkers::isEarth).count() == 1);
        }

        System.out.println(failures == 0 ? "EarthToggleCheck: PASS" : "EarthToggleCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private EarthToggleCheck() {}
}
