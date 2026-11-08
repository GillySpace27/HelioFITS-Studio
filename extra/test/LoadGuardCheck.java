package org.helioviewer.jhv.app.state;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The estimate behind the download guard: what a session's JSON says it will fetch, and what the
 * cache already holds. Reads the JSON only (no network), so it runs anywhere.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.app.state.LoadGuardCheck
 */
public final class LoadGuardCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static JSONObject layer(String name, JSONObject data) {
        return new JSONObject().put("name", name).put("data", data);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("user.home", java.nio.file.Files.createTempDirectory("hfs-load-guard").toString());
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice(); // the request times are parsed by TimeUtils
        // The reported case: a month at 12 s is 216,001 frames in 240 pieces, and must trip the guard.
        JSONObject month = new JSONObject().put("APIRequest", new JSONObject().put("startTime", "2026-10-01T00:00:00")
                .put("endTime", "2026-10-31T00:00:00").put("cadence", 12).put("sourceId", 10).put("server", "x"));
        JSONArray layers = new JSONArray().put(layer("AIA 171", month));
        LoadGuard.Estimate e = LoadGuard.estimate(layers, uri -> false);
        expect("a month at 12 s is 216,001 frames, got " + e.frames(), e.frames() == 216_001);
        expect("and trips the default limits", e.over(LoadGuard.DEFAULT_FRAMES, LoadGuard.DEFAULT_MB * 1_000_000L));
        expect("and that layer is the one held", e.downloads(0));

        // A modest request stays under both limits.
        JSONObject day = new JSONObject().put("APIRequest", new JSONObject().put("startTime", "2026-10-01T00:00:00")
                .put("endTime", "2026-10-02T00:00:00").put("cadence", 3600));
        LoadGuard.Estimate small = LoadGuard.estimate(new JSONArray().put(layer("day", day)), uri -> false);
        expect("a day hourly is 25 frames, got " + small.frames(), small.frames() == 25);
        expect("and does not trip", !small.over(LoadGuard.DEFAULT_FRAMES, LoadGuard.DEFAULT_MB * 1_000_000L));

        // Files: cached ones are not downloaded, local ones are not downloads at all.
        JSONArray uris = new JSONArray().put("https://a/1.fits").put("https://a/2.fits").put("https://a/3.fits").put("file:///x/4.fits");
        LoadGuard.Estimate files = LoadGuard.estimate(new JSONArray().put(layer("PUNCH", new JSONObject().put("uris", uris))),
                uri -> uri.toString().endsWith("1.fits"));
        expect("three remote files, one cached: two to download, got " + files.frames(), files.frames() == 2 && files.cached() == 1);
        expect("sized at the FITS assumption", files.bytes() == 2 * LoadGuard.FITS_FRAME_BYTES);

        // Two layers: the size limit alone trips it (40 PUNCH mosaics are over 1 GB while under 1000 frames).
        JSONArray forty = new JSONArray();
        for (int i = 0; i < 41; i++)
            forty.put("https://a/" + i + ".fits");
        LoadGuard.Estimate big = LoadGuard.estimate(new JSONArray().put(layer("big", new JSONObject().put("uris", forty))), uri -> false);
        expect("41 mosaics trip on size though far under the frame limit",
                big.frames() < LoadGuard.DEFAULT_FRAMES && big.over(LoadGuard.DEFAULT_FRAMES, LoadGuard.DEFAULT_MB * 1_000_000L));
        expect("fully cached costs nothing", LoadGuard.estimate(new JSONArray().put(layer("c", new JSONObject().put("uris", forty))), uri -> true).bytes() == 0);

        // A layer that only remembers a query has no count yet; it is reported, not guessed.
        LoadGuard.Estimate q = LoadGuard.estimate(new JSONArray().put(layer("q", new JSONObject().put("fitsRequest", new JSONObject()))), uri -> false);
        expect("a bare archive query is unknown, not zero-and-hidden", q.items().size() == 1 && q.items().getFirst().unknown());

        // A postponed layer loads nothing, is not mistaken for a failed one, and a save still writes what it would load.
        JSONObject held = new JSONObject().put("uris", new JSONArray().put("https://a/1.fits").put("https://a/2.fits"));
        org.helioviewer.jhv.layers.ImageLayer layer = org.helioviewer.jhv.layers.ImageLayer.createDetached(held, true);
        expect("the layer is held", layer.isHeld());
        expect("a held layer starts no load", !layer.isLoadingView());
        expect("a held layer counts as finished, so the restore does not wait on it", layer.isViewLoadFinished());
        JSONObject saved = new JSONObject();
        layer.serialize(saved);
        expect("a save keeps the postponed URIs, got " + saved.optJSONArray("uris"), saved.optJSONArray("uris") != null && saved.getJSONArray("uris").length() == 2);

        System.out.println(failures == 0 ? "LoadGuardCheck: all ok" : "LoadGuardCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
