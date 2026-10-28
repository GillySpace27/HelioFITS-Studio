package org.helioviewer.jhv.app.state;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.helioviewer.jhv.io.APIRequest;
import org.helioviewer.jhv.io.FitsRequest;
import org.helioviewer.jhv.time.TimeUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A template survives being written and read, and opening one moves the scene where its time rule
 * says. The session is written through State's own writer (State.writeNowConfirmed) and read back as
 * LoadState reads it; opening runs TemplateState.prepare, the step State.load takes before it builds
 * any layer, and the requests are then parsed by the records the layers build from (APIRequest,
 * FitsRequest). No window: the narrowest part of the load path that carries the rule.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.app.state.TemplateRoundTripCheck
 */
public final class TemplateRoundTripCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final long HOUR = 3_600_000L;
    private static final long NOW = TimeUtils.parse("2026-10-24T12:00:00");
    private static final String SAVED_START = "2024-05-10T00:00:00.000", SAVED_END = "2024-05-12T00:00:00.000";

    // A scene as State.toJson writes one: an AIA layer from Helioviewer, a LASCO FITS query with its
    // cached file list, and a local file with no query.
    private static JSONObject scene() {
        JSONObject api = new JSONObject().put("server", "ROB").put("sourceId", 10)
                .put("startTime", SAVED_START).put("endTime", SAVED_END).put("cadence", 1800);
        JSONObject fits = new JSONObject().put("archive", "LASCO").put("level", "L1").put("product", "C2")
                .put("version", "latest").put("cadence", 0).put("startTime", SAVED_START).put("endTime", SAVED_END);
        JSONArray images = new JSONArray()
                .put(layer("AIA 171", new JSONObject().put("APIRequest", api)))
                .put(layer("LASCO C2", new JSONObject().put("fitsRequest", fits).put("uris", new JSONArray().put("https://example.org/c2.fts"))))
                .put(layer("Local", new JSONObject().put("uris", new JSONArray().put("file:/tmp/local.fits"))));
        JSONObject main = new JSONObject().put("time", SAVED_END).put("masterStartTime", SAVED_START).put("masterEndTime", SAVED_END)
                .put("layers", new JSONArray()).put("imageLayers", images);
        return new JSONObject().put("org.helioviewer.jhv.state", main);
    }

    private static JSONObject layer(String name, JSONObject data) {
        return new JSONObject().put("className", "org.helioviewer.jhv.layers.ImageLayer").put("name", name).put("data", data);
    }

    // As LoadState reads a file: the inside of the session object.
    private static JSONObject writeAndRead(JSONObject session, Path dir, String file) throws Exception {
        expect("State.writeNowConfirmed writes " + file, State.writeNowConfirmed(session, dir.toString(), file));
        return new JSONObject(Files.readString(dir.resolve(file))).getJSONObject("org.helioviewer.jhv.state");
    }

    private static JSONObject data(JSONObject state, int i) {
        return state.getJSONArray("imageLayers").getJSONObject(i).getJSONObject("data");
    }

    private static TemplateTime.Meta meta(TemplateTime.Mode mode) {
        return new TemplateTime.Meta("Round trip", "A check", mode, Duration.ofHours(2), Duration.ZERO, "Round trip.png", TemplateTime.VERSION);
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("hfs-template-roundtrip");
        System.setProperty("user.home", home.toString()); // never the real settings
        // As BlankSessionCheck: the request records parse their times through SPICE, as at startup.
        org.helioviewer.jhv.app.Platform.init();
        org.helioviewer.jhv.io.Directories.createPersistentDirs();
        org.helioviewer.jhv.io.Directories.createCacheDirs();
        org.helioviewer.jhv.app.AppInit.loadSpice();
        org.helioviewer.jhv.io.DataSources.initSources(); // APIRequest.fromJson checks the server against them
        org.helioviewer.jhv.app.Settings.load();
        Path dir = Files.createDirectories(home.resolve("out"));

        // A plain session: no template object, nothing moves.
        JSONObject plain = writeAndRead(scene(), dir, "plain.jhv");
        String before = plain.toString();
        expect("a plain session has no template", TemplateState.prepare(plain, NOW, (s, id) -> Long.MIN_VALUE) == null);
        expect("and prepare leaves it as it was", before.equals(plain.toString()));

        // fixed: the template comes back intact and the dates do not move.
        TemplateTime.Meta fixedMeta = meta(TemplateTime.Mode.FIXED);
        JSONObject fixed = writeAndRead(TemplateState.attach(scene(), fixedMeta), dir, "fixed.jhv");
        expect("the template object is beside the layers", fixed.optJSONObject(TemplateState.KEY) != null);
        expect("the template reads back intact", fixedMeta.equals(TemplateState.meta(fixed)));
        String fixedBefore = fixed.toString();
        expect("prepare returns the fixed template", fixedMeta.equals(TemplateState.prepare(fixed, NOW, (s, id) -> NOW - HOUR)));
        expect("a fixed template's scene is unchanged", fixedBefore.equals(fixed.toString()));
        expect("its APIRequest keeps the saved dates", SAVED_START.equals(data(fixed, 0).getJSONObject("APIRequest").getString("startTime")));
        expect("its FITS layer keeps its file list", data(fixed, 1).has("uris"));

        // latest, newest unknown: every request and the master range become [now - 2 h, now].
        JSONObject latest = writeAndRead(TemplateState.attach(scene(), meta(TemplateTime.Mode.LATEST)), dir, "latest.jhv");
        expect("prepare returns the latest template", TemplateState.prepare(latest, NOW, (s, id) -> Long.MIN_VALUE) != null);
        APIRequest api = APIRequest.fromJson(data(latest, 0).getJSONObject("APIRequest"));
        expect("the APIRequest is [now - 2 h, now]: " + TimeUtils.format(api.startTime()) + " to " + TimeUtils.format(api.endTime()),
                api.startTime() == NOW - 2 * HOUR && api.endTime() == NOW);
        FitsRequest fits = FitsRequest.fromJson(data(latest, 1).getJSONObject("fitsRequest"));
        expect("the fitsRequest is [now - 2 h, now]", fits.startTime() == NOW - 2 * HOUR && fits.endTime() == NOW);
        expect("the FITS layer's old file list is dropped, so it re-runs the query", !data(latest, 1).has("uris"));
        expect("and it is still restorable", State.hasRestorableData(data(latest, 1)));
        expect("a layer with only files keeps them", data(latest, 2).getJSONArray("uris").length() == 1);
        expect("the master range is [now - 2 h, now]",
                TimeUtils.parse(latest.getString("masterStartTime")) == NOW - 2 * HOUR && TimeUtils.parse(latest.getString("masterEndTime")) == NOW);
        expect("the playhead is at the end", TimeUtils.parse(latest.getString("time")) == NOW);

        // latest, newest known from the catalog: the range ends there.
        JSONObject catalog = writeAndRead(TemplateState.attach(scene(), meta(TemplateTime.Mode.LATEST)), dir, "catalog.jhv");
        TemplateState.prepare(catalog, NOW, (s, id) -> "ROB".equals(s) && id == 10 ? NOW - 3 * HOUR : Long.MIN_VALUE);
        expect("with the AIA source's newest time known, the range ends there",
                TimeUtils.parse(catalog.getString("masterEndTime")) == NOW - 3 * HOUR
                        && TimeUtils.parse(data(catalog, 0).getJSONObject("APIRequest").getString("startTime")) == NOW - 5 * HOUR);

        // A malformed template is refused and leaves the scene alone.
        JSONObject broken = writeAndRead(scene(), dir, "broken.jhv");
        broken.put(TemplateState.KEY, new JSONObject().put("name", "Broken").put("time", new JSONObject().put("mode", "latest").put("span", "2 hours")));
        String brokenBefore = broken.toString();
        boolean refused = false;
        try {
            TemplateState.prepare(broken, NOW, (s, id) -> Long.MIN_VALUE);
        } catch (IllegalArgumentException e) {
            refused = e.getMessage().contains("span");
        }
        expect("a template with a bad span is refused, naming the span", refused);
        expect("and the scene is left as it was", brokenBefore.equals(broken.toString()));

        expect("the datasets read from the layer names: " + TemplateState.datasets(latest),
                "AIA 171, LASCO C2, Local".equals(TemplateState.datasets(latest)));

        // The user's library: a saved template is listed, a session is never written over one, retire moves it.
        expect("safeName keeps a plain name and replaces the rest: " + TemplateLibrary.safeName(" Sun/pizza: v2 "),
                "Sun_pizza_ v2".equals(TemplateLibrary.safeName(" Sun/pizza: v2 ")) && "template".equals(TemplateLibrary.safeName("..")));
        TemplateTime.Meta libMeta = meta(TemplateTime.Mode.LATEST).withName("Library one").withThumbnail("");
        java.io.File saved = TemplateLibrary.save(scene(), libMeta);
        expect("save writes Templates/Library one.jhv", saved.isFile() && saved.getParentFile().equals(TemplateLibrary.userDir()));
        expect("all() lists it with its template", TemplateLibrary.all().stream().anyMatch(e -> !e.builtIn() && libMeta.equals(e.meta())));
        expect("isTemplate says so", TemplateLibrary.isTemplate(saved) && !TemplateLibrary.isTemplate(dir.resolve("plain.jhv").toFile()));
        expect("a plain session is not written over a template", !State.writeNowConfirmed(scene(), saved.getParent(), saved.getName()));
        expect("so the file is still a template", TemplateLibrary.isTemplate(saved));
        TemplateLibrary.Entry entry = TemplateLibrary.all().stream().filter(e -> libMeta.equals(e.meta())).findFirst().orElseThrow();
        java.io.File retired = TemplateLibrary.retire(entry);
        expect("retire moves it into Templates/attic/", !saved.exists() && retired.isFile() && "attic".equals(retired.getParentFile().getName()));
        expect("and it is no longer listed", TemplateLibrary.all().stream().noneMatch(e -> libMeta.equals(e.meta())));

        System.out.println(failures == 0 ? "TemplateRoundTripCheck: ok" : "TemplateRoundTripCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
