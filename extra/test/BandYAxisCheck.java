package org.helioviewer.jhv.timelines.band;

import org.helioviewer.jhv.timelines.TimelineLayers;
import org.helioviewer.jhv.timelines.draw.DrawController;

import org.json.JSONObject;

/**
 * The y axis of a band: auto-fit follows the visible data, a typed range is honoured and turns
 * auto-fit off, a log axis refuses a range that reaches zero, and the settings survive a state save.
 */
public final class BandYAxisCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= 1e-6 * Math.max(1, Math.abs(b));
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-band-yaxis").toString());
        System.setProperty("java.awt.headless", "true");

        JSONObject jo = new JSONObject().put("name", "Test").put("baseUrl", "").put("label", "Test")
                .put("unitLabel", "K").put("range", new org.json.JSONArray().put(0).put(10)).put("scale", "linear");
        Band band = new Band(new BandType(jo), true);
        TimelineLayers model = new TimelineLayers();
        model.add(band);
        DrawController.setGraphSize(800, 400);

        long t0 = 1_700_000_000_000L;
        long hour = 3_600_000L;
        DrawController.setSelectedInterval(t0, t0 + 10 * hour);
        band.addToCache(new float[]{100, 200, 300, 0.5f}, new long[]{t0 + hour, t0 + 2 * hour, t0 + 3 * hour, t0 + 20 * hour});

        expect("starts with auto-fit off and the type's range", !band.isAutoFit() && band.yMin() == 0 && band.yMax() == 10);

        band.setAutoFit(true);
        expect("auto-fit covers the visible data with a margin, ignoring the point outside the window",
                band.yMin() < 100 && band.yMin() > 80 && band.yMax() > 300 && band.yMax() < 320);

        DrawController.setSelectedInterval(t0, t0 + 3 * hour / 2); // holds only the 100 K sample
        expect("auto-fit follows the window", band.yMin() < 100 && band.yMax() > 100 && band.yMax() < 120);
        DrawController.setSelectedInterval(t0, t0 + 10 * hour);

        expect("a typed range is accepted", band.setYRange(50, 400));
        expect("a typed range sets the axis and turns auto-fit off", band.yMin() == 50 && band.yMax() == 400 && !band.isAutoFit());
        expect("an inverted range is refused", !band.setYRange(5, 1) && band.yMin() == 50);

        band.yaxisChanged();
        band.setAutoFit(true);
        band.yaxisChanged();
        expect("dragging the axis turns auto-fit off", !band.isAutoFit());

        JSONObject saved = new JSONObject();
        band.setYRange(60, 350);
        band.serialize(saved);
        Band back = (Band) Band.deserialize(saved);
        expect("the range survives a state round trip", near(back.yMin(), 60) && near(back.yMax(), 350) && !back.isAutoFit() && !back.isLogScale());

        // one distinct value in view: Fit pads around it instead of falling back to the type's range
        DrawController.setSelectedInterval(t0, t0 + 3 * hour / 2);
        band.zoomToFitAxis();
        expect("Fit on a single value brackets it", band.yMin() < 100 && band.yMax() > 100 && band.yMax() < 110);

        DrawController.setSelectedInterval(t0, t0 + 10 * hour);

        band.setLogScale(true);
        expect("log scale turns auto-fit on and keeps a positive range", band.isLogScale() && band.isAutoFit() && band.yMin() > 0);
        expect("log refuses a range reaching zero", !band.setYRange(0, 10));
        expect("log accepts a positive range", band.setYRange(1, 1000) && band.yMin() == 1);
        JSONObject savedLog = new JSONObject();
        band.serialize(savedLog);
        Band backLog = (Band) Band.deserialize(savedLog);
        expect("log scale survives a state round trip", backLog.isLogScale() && near(backLog.yMax(), 1000));

        Band legacy = (Band) Band.deserialize(new JSONObject().put("bandType", jo));
        expect("a state saved before the controls keeps the type's defaults", !legacy.isAutoFit() && legacy.yMin() == 0 && legacy.yMax() == 10);

        System.exit(failures > 0 ? 1 : 0);
    }
}
