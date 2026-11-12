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

        // The review's starting point, per layer: postpone only what is alone over a limit.
        long mf = LoadGuard.DEFAULT_FRAMES, mb = LoadGuard.DEFAULT_MB * 1_000_000L;
        expect("a month at 12 s starts on Postpone", e.items().getFirst().postponeByDefault(mf, mb));
        expect("a day hourly starts on load", !small.items().getFirst().postponeByDefault(mf, mb));
        expect("41 mosaics, over the size limit alone, start on Postpone", big.items().getFirst().postponeByDefault(mf, mb));
        expect("a fully cached layer starts on load", !LoadGuard.estimate(new JSONArray().put(layer("c", new JSONObject().put("uris", forty))), uri -> true)
                .items().getFirst().postponeByDefault(mf, mb));
        expect("an unknown layer starts on load", !q.items().getFirst().postponeByDefault(mf, mb));
        LoadGuard.Estimate mixed = LoadGuard.estimate(new JSONArray().put(layer("a", month)).put(layer("b", day)), uri -> false);
        expect("when nobody can be asked, every layer with something to download is held", mixed.downloading().equals(java.util.Set.of(0, 1)));

        // The review's outcome: Cancel drops a layer from the load, Postpone holds it, Download leaves it alone.
        LoadGuard.Review r = LoadGuard.review(mixed.items(), new LoadGuard.Choice[]{LoadGuard.Choice.CANCEL, LoadGuard.Choice.POSTPONE});
        expect("Cancel drops layer 0, Postpone holds layer 1", r.dropped().equals(java.util.Set.of(0)) && r.held().equals(java.util.Set.of(1)));
        r = LoadGuard.review(mixed.items(), new LoadGuard.Choice[]{LoadGuard.Choice.DOWNLOAD, LoadGuard.Choice.DOWNLOAD});
        expect("Download all holds and drops nothing", r.held().isEmpty() && r.dropped().isEmpty());
        r = LoadGuard.review(mixed.items(), new LoadGuard.Choice[]{LoadGuard.Choice.CANCEL, LoadGuard.Choice.CANCEL});
        expect("Cancel all drops every layer", r.dropped().equals(java.util.Set.of(0, 1)) && r.held().isEmpty());

        expect("Cancel beside Go aborts the whole load", LoadGuard.Review.ABORT.aborted() && !LoadGuard.Review.NONE.aborted());
        expect("a layer-level review never aborts", !r.aborted());

        // Ticked event groups are rows too, at negative places, and trip the guard on a long range.
        long t0 = java.time.Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), dayMs = 86_400_000L;
        LoadGuard.Item month1 = LoadGuard.events(-1, "Events: Flare", t0, t0 + 30 * dayMs);
        expect("a month of one group is 450 events, got " + month1.frames(), month1.frames() == 450 && month1.at() == -1);
        LoadGuard.Estimate withEvents = small.plus(java.util.List.of(month1, LoadGuard.events(-2, "Events: CME", t0, t0 + 30 * dayMs), LoadGuard.events(-3, "Events: AR", t0, t0 + 30 * dayMs)));
        expect("three groups over a month trip the frame limit though the layers alone do not",
                withEvents.over(LoadGuard.DEFAULT_FRAMES, LoadGuard.DEFAULT_MB * 1_000_000L) && !small.over(LoadGuard.DEFAULT_FRAMES, LoadGuard.DEFAULT_MB * 1_000_000L));
        r = LoadGuard.review(withEvents.items(), new LoadGuard.Choice[]{LoadGuard.Choice.DOWNLOAD, LoadGuard.Choice.POSTPONE, LoadGuard.Choice.CANCEL, LoadGuard.Choice.DOWNLOAD});
        expect("an event row is held and dropped by its negative place", r.held().equals(java.util.Set.of(-1)) && r.dropped().equals(java.util.Set.of(-2)));
        expect("a short range is a day's worth at least", LoadGuard.events(-1, "x", t0, t0 + 1000).frames() == 15);

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
