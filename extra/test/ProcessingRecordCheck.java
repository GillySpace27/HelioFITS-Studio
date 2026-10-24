package org.helioviewer.jhv.movie;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.io.HeliadLibrary;
import org.helioviewer.jhv.io.SourceIdentity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The processing record (hfrecord 1.0) built from fixed sessions with no window: steps in the
 * order the code runs them, the clip bounds actually used, varies for automation tracks and for
 * settings changed during a recording, the difference reference and Compare, the suite
 * provenance object, and no path anywhere. It also writes each record to
 * extra/test-classes/hfrecord/, where extra/test/test_hfrecord.py validates them against
 * docs/spec/hfrecord-1.schema.json (the hfrecord step of ant check-all).
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.movie.ProcessingRecordCheck
 */
public final class ProcessingRecordCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final String HOME = "/Users/someone";
    private static final String A = "00000000-0000-4000-8000-00000000000a";
    private static final String B = "00000000-0000-4000-8000-00000000000b";

    private static JSONObject params(String lut, double gamma, double opacity) {
        return new JSONObject().put("sharpen", 0).put("opacity", opacity).put("blend", 1).put("slitLeft", 0).put("slitRight", 1)
                .put("sectorCenter", 0).put("sectorWidth", 0).put("innerMask", 0).put("outerMask", JSONObject.NULL)
                .put("brightOffset", 0).put("brightScale", 1).put("enhanced", 0).put("upsilonLow", 0.6).put("upsilonHigh", 0.4)
                .put("differenceMode", "None").put("color", new JSONObject().put("red", true).put("green", true).put("blue", true))
                .put("invert", false).put("colorbar", false).put("lut", lut).put("clippingMode", "Percentile05")
                .put("clippingMin", -500).put("clippingMax", 500).put("scalingMode", "Gamma").put("gamma", gamma)
                .put("beta", 0.015625).put("alpha", 1000).put("plane", 0);
    }

    private static JSONObject scene(double gamma, boolean automation) {
        String lib = "file:" + HOME + "/HeliadLibrary/store/aia/";
        JSONObject a = new JSONObject().put("className", "org.helioviewer.jhv.layers.ImageLayer").put("name", "SDO AIA 193").put("id", A)
                .put("enabled", true).put("data", new JSONObject()
                        .put("uris", new JSONArray().put(lib + "a1.fits").put(lib + "a2.fits"))
                        .put("imageParams", params("SDO-AIA 193 Å", gamma, 1)).put("filter", "RHEF"));
        JSONObject bp = params("Gray", 0.45, 0.8).put("differenceMode", "Running");
        JSONObject b = new JSONObject().put("className", "org.helioviewer.jhv.layers.ImageLayer").put("name", "AIA 171").put("id", B)
                .put("enabled", true).put("data", new JSONObject()
                        .put("APIRequest", new JSONObject().put("server", "ROB").put("sourceId", 10))
                        .put("imageParams", bp).put("filter", "None"));
        JSONObject state = new JSONObject().put("time", "2025-09-21T20:32:00").put("masterStartTime", "2025-09-21T20:00:00")
                .put("masterEndTime", "2025-09-21T21:00:00")
                .put("imageLayers", new JSONArray().put(a).put(b))
                .put("layers", new JSONArray().put(new JSONObject().put("name", "Grid").put("enabled", true)))
                .put("compare", new JSONObject().put("mode", "Difference").put("rate", 2).put("split", 0.5).put("aLeft", true).put("a", A).put("b", B));
        if (automation)
            state.put("automation", new JSONObject().put("tracks", new JSONArray()
                    .put(new JSONObject().put("key", "layer:" + A + "/opacity").put("enabled", true)
                            .put("keys", new JSONArray().put(new JSONObject().put("t", "2025-09-21T20:00:00").put("v", 1).put("i", "LINEAR"))))
                    .put(new JSONObject().put("key", "display.warpLambda").put("enabled", true)
                            .put("keys", new JSONArray().put(new JSONObject().put("t", "2025-09-21T20:00:00").put("v", 0.5).put("i", "LINEAR"))))));
        return new JSONObject().put("org.helioviewer.jhv.state", state);
    }

    private static final HeliadLibrary.Identity HELIAD = new HeliadLibrary.Identity("1".repeat(64), "sdo/aia/-/-/1/193",
            "sdo/aia/-/-/1/193|2025-09-21T20:32:04.840", "ds-7f3a", null, 1000, "2025-09-21T20:32:04.840", "SDO AIA 193 Å L1");

    private static SourceIdentity.Entry identify(URI uri) {
        String u = uri.toString();
        if (!u.startsWith("file:" + HOME + "/")) // the record must look files up by their real path, home put back
            return null;
        return u.endsWith("a1.fits") ? new SourceIdentity.Entry("1".repeat(64), 1000, HELIAD)
                : new SourceIdentity.Entry("2".repeat(64), 1000, new HeliadLibrary.Identity("2".repeat(64), "sdo/aia/-/-/1/193",
                        "sdo/aia/-/-/1/193|2025-09-21T20:33:04.840", "ds-7f3a", null, 1000, null, null));
    }

    private static JSONObject live() {
        return new JSONObject().put("layers", new JSONObject()
                        .put(A, new JSONObject().put("fits", true).put("frame", 0).put("frames", 2)
                                .put("clip", new JSONArray().put(12.5).put(3400.25)).put("clip_from", "decoded frame").put("credit_key", "SDO/AIA"))
                        .put(B, new JSONObject().put("fits", false).put("frame", 3).put("frames", 10)))
                .put("frame", Provenance.frame(0, false, "2025-09-21T20:32:00.000", "Earth", 0, 0, 215, "Orthographic", "Viewpoint", 6.4, 1, null));
    }

    private static List<String> ops(JSONObject layer) {
        List<String> out = new ArrayList<>();
        for (Object o : layer.getJSONArray("steps"))
            out.add(((JSONObject) o).getString("op"));
        return out;
    }

    private static JSONObject stepOf(JSONObject layer, String op) {
        for (Object o : layer.getJSONArray("steps"))
            if (op.equals(((JSONObject) o).getString("op")))
                return (JSONObject) o;
        return null;
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of("extra", "test-classes", "hfrecord");
        Files.createDirectories(out);

        JSONObject session = Provenance.session(scene(0.45, false), HOME, Instant.parse("2026-10-23T00:00:00Z"), u -> null);
        ProcessingRecord.Output still = new ProcessingRecord.Output("still", "image/png", 2048, 2048, 1, null, "png", 16, true);
        JSONObject r = ProcessingRecord.build(session, live(), null, still, "frame0001.png", "a".repeat(64), HOME,
                ProcessingRecordCheck::identify, "00000000-0000-4000-8000-000000000001");
        ProcessingRecord.writeJson(out.resolve("still.hfrec.json"), r);

        expect("format 1.0, reader_compat 1", "1.0".equals(r.getString("hfrecord")) && r.getInt("reader_compat") == 1);
        expect("the output names the file and its sha256", "frame0001.png".equals(r.getJSONObject("output").getString("file_name"))
                && "a".repeat(64).equals(r.getJSONObject("output").getString("sha256")));
        JSONObject a = r.getJSONArray("layers").getJSONObject(0), b = r.getJSONArray("layers").getJSONObject(1);
        expect("FITS layer steps in code order: " + ops(a), ops(a).equals(List.of("select_plane", "clip", "stretch", "image_filter",
                "geometric_mask", "levels", "radial_gain", "difference", "sharpen", "rhef_upsilon", "colormap", "channel_mask", "opacity_blend")));
        expect("a layer that does not decode FITS has no clip or stretch: " + ops(b), !ops(b).contains("clip") && !ops(b).contains("stretch"));
        JSONObject clip = stepOf(a, "clip");
        expect("clip carries the bounds actually used",
                clip.getJSONObject("resolved").getDouble("low") == 12.5 && clip.getJSONObject("resolved").getDouble("high") == 3400.25);
        expect("RHEF carries its stamp, caveat and citation", stepOf(a, "image_filter").getString("stamp").startsWith("RHEF ")
                && "not a calibrated radiance".equals(stepOf(a, "image_filter").getString("caveat")));
        expect("Compare Difference: layer a's difference names b", B.equals(stepOf(a, "difference").getJSONObject("reference").getString("layer_id"))
                && "compare".equals(stepOf(a, "difference").getJSONObject("reference").getString("kind")));
        expect("a Running difference is referenced as running", "running".equals(stepOf(b, "difference").getJSONObject("reference").getString("kind")));
        expect("scene.compare is the session's Compare", "Difference".equals(r.getJSONObject("scene").getJSONObject("compare").getString("mode")));
        expect("library sources by sha256 and obs_key, with the dataset",
                a.getJSONArray("sources").length() == 2 && "1".repeat(64).equals(a.getJSONArray("sources").getJSONObject(0).getString("sha256"))
                        && a.getJSONArray("sources").getJSONObject(0).has("heliad_obs_key")
                        && "ds-7f3a".equals(a.getJSONObject("dataset").getString("dataset_uid"))
                        && "heliad_library".equals(a.getJSONObject("source_spec").getString("kind")));
        expect("source_set_sha256 is the hash of the sorted hashes",
                ProcessingRecord.setHash(List.of("2".repeat(64), "1".repeat(64))).equals(a.getString("source_set_sha256")));
        expect("a Helioviewer layer is a helioviewer_api source_spec", "helioviewer_api".equals(b.getJSONObject("source_spec").getString("kind")));
        JSONObject prov = r.getJSONObject("provenance");
        expect("suite provenance has exactly its seven fields: " + prov.keySet(), prov.keySet().equals(java.util.Set.of("product", "version",
                "instrument", "observed", "credit", "rhef", "citation")));
        expect("the credit is the AIA layer's instrument credit: " + prov.getString("credit"),
                InstrumentCredit.line(List.of("SDO/AIA")).equals(prov.getString("credit")) && prov.getString("credit").contains("NASA/SDO"));
        expect("a layer with a known instrument carries its acknowledgement, one without carries null",
                a.getString("acknowledgement").contains("NASA/SDO") && b.isNull("acknowledgement"));
        expect("observed is seconds with Z, rhef the stamp", "2025-09-21T20:32:00Z".equals(prov.getString("observed"))
                && prov.getString("rhef").startsWith("RHEF ") && prov.isNull("citation"));
        expect("the native part is session() verbatim", session.similar(r.getJSONObject("native").getJSONObject("studio_session")));
        expect("a still did not change during its export", !r.getJSONObject("snapshot").getBoolean("changed_during_export"));
        expect("the summary reads top-down", r.getString("summary").contains("1. SDO AIA 193 Å L1: 2 source frames, RHEF, LUT SDO-AIA 193 Å")
                && r.getString("summary").contains("Compare: Difference"));
        expect("the RHEF disclaimer is a caveat", r.getJSONArray("caveats").toList().contains(ProcessingRecord.SUITE_DISCLAIMER));
        expect("no path on the computer anywhere in the record", !r.toString().contains(HOME));

        // A movie: an automation track on a's opacity, and gamma changed by hand while recording.
        JSONObject mSession = Provenance.session(scene(0.45, true), HOME, Instant.parse("2026-10-23T00:00:00Z"), u -> null);
        ProcessingRecord.Output movie = new ProcessingRecord.Output("movie", "video/mp4", 1920, 1080, 120, 24, "H.264", 8, true);
        JSONObject m = ProcessingRecord.build(mSession, live(), scene(0.6, true), movie, "movie.mp4", "b".repeat(64), HOME,
                ProcessingRecordCheck::identify, "00000000-0000-4000-8000-000000000002");
        ProcessingRecord.writeJson(out.resolve("movie.hfrec.json"), m);
        JSONObject ma = m.getJSONArray("layers").getJSONObject(0);
        JSONObject ob = stepOf(ma, "opacity_blend").optJSONObject("varies");
        expect("an automated parameter varies, pointing at its track", ob != null && ob.getJSONArray("automation").getString(0).equals("layer:" + A + "/opacity")
                && !ob.getBoolean("changed_by_user"));
        JSONObject st = stepOf(ma, "stretch").optJSONObject("varies");
        expect("a parameter changed during the recording varies, by the user", st != null && st.getBoolean("changed_by_user")
                && st.getJSONArray("params").toList().contains("gamma"));
        expect("and the snapshot says the scene changed", m.getJSONObject("snapshot").getBoolean("changed_during_export"));
        expect("scene-wide tracks are named in scene.varies", m.getJSONObject("scene").getJSONObject("varies").getJSONArray("automation")
                .toList().contains("display.warpLambda"));
        expect("a movie has a time range", m.getJSONObject("scene").getJSONArray("time_range").length() == 2);
        JSONObject unchanged = ProcessingRecord.build(mSession, live(), scene(0.45, true), movie, "movie.mp4", null, HOME,
                ProcessingRecordCheck::identify, "00000000-0000-4000-8000-000000000003");
        expect("an unchanged recording is not changed", !unchanged.getJSONObject("snapshot").getBoolean("changed_during_export")
                && stepOf(unchanged.getJSONArray("layers").getJSONObject(0), "stretch").optJSONObject("varies") == null);

        // Send to Python: physical values, decode steps only.
        JSONObject entry = new JSONObject().put("source", "a1.fits").put("file", "a1_phys.fits").put("steps", new JSONArray()
                .put("read as double from the source file").put("subtracted OFFSET=470.0 DN (the CCD bias card in the header; the same step the viewer takes)"));
        JSONObject f = ProcessingRecord.buildFitsData(session, A, "SDO AIA 193", "SOHO/LASCO", new SourceIdentity.Entry("1".repeat(64), 1000, HELIAD),
                entry, "a1_phys.fits", "c".repeat(64), "00000000-0000-4000-8000-000000000004");
        ProcessingRecord.writeJson(out.resolve("fits.hfrec.json"), f);
        JSONObject fl = f.getJSONArray("layers").getJSONObject(0);
        expect("a FITS data record lists only the decode step it applied: " + ops(fl), ops(fl).equals(List.of("instrument_correction")));
        expect("and is not display-referred", !f.getJSONObject("output").getBoolean("display_referred")
                && "fits_data".equals(f.getJSONObject("output").getString("kind")));
        expect("and carries no RHEF stamp", f.getJSONObject("provenance").isNull("rhef"));
        expect("and credits the instrument it was given", f.getJSONObject("provenance").getString("credit").startsWith("Courtesy of SOHO/LASCO")
                && fl.getString("acknowledgement").startsWith("The SOHO/LASCO data"));

        expect("the sidecar is the full file name plus .hfrec.json",
                ProcessingRecord.sidecar(Path.of("/x/frame0001.png")).equals(Path.of("/x/frame0001.png.hfrec.json")));

        if (failures != 0) {
            System.out.println(failures + " processing record failure(s)");
            System.exit(1);
        }
    }
}
