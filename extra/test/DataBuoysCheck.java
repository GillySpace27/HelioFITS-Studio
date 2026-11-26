package org.helioviewer.jhv.layers.grid;

import org.json.JSONObject;

/**
 * Buoys (3D markers for the spacecraft behind timeline rows): a row name finds its spacecraft by
 * whole words, a name that merely contains a spacecraft's letters finds none, and the four settings
 * survive a session round trip and read as their defaults from a session that predates them.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.grid.DataBuoysCheck
 */
public final class DataBuoysCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static String key(String name) {
        DataBuoys.Craft c = DataBuoys.infer(name);
        return c == null ? null : c.key();
    }

    public static void main(String[] args) {
        expect("PSP row names the probe", "psp".equals(key("PSP SWEAP proton speed")));
        expect("Solar Orbiter written out", "solo".equals(key("Solar Orbiter MAG |B|")));
        expect("STEREO-A with a hyphen", "sta".equals(key("STEREO-A PLASTIC density")));
        expect("ACE as a word", "ace".equals(key("ACE SWEPAM proton density")));
        expect("'space' is not ACE", key("Space weather index") == null);
        expect("'solar wind' alone is not Wind", key("Solar wind speed") == null);
        expect("Wind with its instrument", "wind".equals(key("Wind SWE proton density")));
        expect("GOES has no spacecraft here", key("GOES XRS long") == null);
        expect("a row with no name finds none", DataBuoys.infer(null) == null);
        expect("byKey finds a craft and not 'none'", DataBuoys.byKey("psp") != null && DataBuoys.byKey("none") == null);

        DataBuoys fresh = new DataBuoys();
        expect("defaults: buoys on, names on hover, halo on, trails off",
                fresh.isShow() && !fresh.isAlwaysLabels() && fresh.isModelHalo() && !fresh.isTrails());
        DataBuoys old = new DataBuoys();
        old.deserialize(new JSONObject().put("showEarth", true));
        expect("a session without the keys reads as the defaults", old.isShow() && old.isModelHalo() && !old.isTrails());
        fresh.setShow(false);
        fresh.setAlwaysLabels(true);
        fresh.setModelHalo(false);
        fresh.setTrails(true);
        JSONObject jo = new JSONObject();
        fresh.serialize(jo);
        DataBuoys back = new DataBuoys();
        back.deserialize(jo);
        expect("settings round trip", !back.isShow() && back.isAlwaysLabels() && !back.isModelHalo() && back.isTrails());

        System.out.println(failures == 0 ? "DataBuoysCheck: ok" : "DataBuoysCheck: " + failures + " FAILED");
        if (failures > 0)
            System.exit(1);
    }
}
