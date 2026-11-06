package org.helioviewer.jhv.movie;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.helioviewer.jhv.io.FitsLibrary;
import org.helioviewer.jhv.io.SourceIdentity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The processing record (hfrecord 1.0) built from fixed sessions with no window: steps in the
 * order the code runs them, the clip bounds actually used, varies for automation tracks and for
 * settings changed during a recording, the difference reference and Compare, the suite
 * provenance object, and no path anywhere. It also writes each record to
 * extra/test-classes/hfrecord/, where extra/test/test_hfrecord.py validates them against
 * docs/spec/hfrecord-1.schema.json (the hfrecord step of ant check-all). The sidecar's preview:
 * made from the raw frames, within the schema's limits, last in the sidecar, kept by a copy, out of
 * the report and out of every embedded copy.
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
        String lib = "file:" + HOME + "/HelioFITSLibrary/store/aia/";
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

    private static final FitsLibrary.Identity HELIAD = new FitsLibrary.Identity("1".repeat(64), "sdo/aia/-/-/1/193",
            "sdo/aia/-/-/1/193|2025-09-21T20:32:04.840", "ds-7f3a", null, 1000, "2025-09-21T20:32:04.840", "SDO AIA 193 Å L1");

    private static SourceIdentity.Entry identify(URI uri) {
        String u = uri.toString();
        if (!u.startsWith("file:" + HOME + "/")) // the record must look files up by their real path, home put back
            return null;
        return u.endsWith("a1.fits") ? new SourceIdentity.Entry("1".repeat(64), 1000, HELIAD)
                : new SourceIdentity.Entry("2".repeat(64), 1000, new FitsLibrary.Identity("2".repeat(64), "sdo/aia/-/-/1/193",
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
        ProcessingRecord.writeJson(out.resolve("still.hfrec"), r);

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
        ProcessingRecord.writeJson(out.resolve("movie.hfrec"), m);
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

        expect("the sidecar is the full file name plus .hfrec",
                ProcessingRecord.sidecar(Path.of("/x/frame0001.png")).equals(Path.of("/x/frame0001.png.hfrec")));
        expect("both sidecar names are recognised and stripped back to the export's",
                ProcessingRecord.isSidecarName("a.png.hfrec") && ProcessingRecord.isSidecarName("a.png.hfrec.json")
                        && !ProcessingRecord.isSidecarName("a.png") && ProcessingRecord.exportName("a.png.hfrec").equals("a.png")
                        && ProcessingRecord.exportName("a.png.hfrec.json").equals("a.png") && ProcessingRecord.exportName("a.png").equals("a.png"));
        Path dir = Files.createTempDirectory("hfrec-names");
        Path png = Files.writeString(dir.resolve("frame.png"), "pixels");
        expect("no sidecar beside a bare export", ProcessingRecord.existingSidecar(png) == null);
        Files.copy(out.resolve("movie.hfrec"), dir.resolve("frame.png.hfrec.json"));
        expect("an older .hfrec.json sidecar is still found", dir.resolve("frame.png.hfrec.json").equals(ProcessingRecord.existingSidecar(png)));
        byte[] legacyBytes = Files.readAllBytes(dir.resolve("frame.png.hfrec.json"));
        Path copy = Files.writeString(dir.resolve("copy.png"), "pixels");
        ProcessingRecord.copySidecar(png, copy);
        expect("copySidecar reads the older name and writes copy.png.hfrec", Files.isRegularFile(dir.resolve("copy.png.hfrec"))
                && !Files.exists(dir.resolve("copy.png.hfrec.json"))
                && "copy.png".equals(new JSONObject(Files.readString(dir.resolve("copy.png.hfrec"))).getJSONObject("output").getString("file_name")));
        expect("and, once read, the older sidecar is renamed to frame.png.hfrec with its bytes untouched",
                !Files.exists(dir.resolve("frame.png.hfrec.json")) && Files.isRegularFile(dir.resolve("frame.png.hfrec"))
                        && java.util.Arrays.equals(legacyBytes, Files.readAllBytes(dir.resolve("frame.png.hfrec"))));
        expect("migrateLegacy leaves a .hfrec name alone", dir.resolve("frame.png.hfrec").equals(ProcessingRecord.migrateLegacy(dir.resolve("frame.png.hfrec"))));
        Files.write(dir.resolve("frame.png.hfrec.json"), "{\"older\": true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        expect(".hfrec is preferred when both are there", dir.resolve("frame.png.hfrec").equals(ProcessingRecord.existingSidecar(png)));
        expect("no clobber: with both there the older one is not renamed over .hfrec",
                dir.resolve("frame.png.hfrec.json").equals(ProcessingRecord.migrateLegacy(dir.resolve("frame.png.hfrec.json")))
                        && Files.exists(dir.resolve("frame.png.hfrec.json"))
                        && java.util.Arrays.equals(legacyBytes, Files.readAllBytes(dir.resolve("frame.png.hfrec"))));
        Path ro = Files.createDirectories(dir.resolve("readonly"));
        Path roSide = Files.write(ro.resolve("a.png.hfrec.json"), legacyBytes);
        java.io.File roDir = ro.toFile();
        if (roDir.setWritable(false, false) && !Files.isWritable(ro)) {
            Path back = ProcessingRecord.migrateLegacy(roSide);
            roDir.setWritable(true, false);
            expect("read-only folder: no rename, no error, the older name is still read",
                    roSide.equals(back) && Files.isRegularFile(roSide) && !Files.exists(ro.resolve("a.png.hfrec")));
        } else {
            roDir.setWritable(true, false);
            System.out.println("  skip read-only rename case: this file system or user can write a read-only folder");
        }

        // Migrate Sidecars in Folder: hfcore record migrate's rule (recursive, records only, no clobber)
        Path mig = Files.createTempDirectory("hfrec-migrate");
        Files.write(mig.resolve("a.png.hfrec.json"), legacyBytes);
        Files.write(mig.resolve("b.png.hfrec.json"), legacyBytes);
        Files.writeString(mig.resolve("b.png.hfrec"), "taken");
        Files.writeString(mig.resolve("c.png.hfrec.json"), "not json");
        Files.writeString(mig.resolve("e.png.hfrec.json"), "{\"other\": 1}");
        Files.write(Files.createDirectories(mig.resolve("sub")).resolve("d.png.hfrec.json"), legacyBytes);
        java.util.Map<String, String> plan = new java.util.TreeMap<>();
        for (String[] row : ProcessingRecord.migrateFolder(mig, false))
            plan.put(Path.of(row[0]).getFileName().toString(), row[2]);
        expect("migrate plan: " + plan, plan.equals(java.util.Map.of("a.png.hfrec.json", "would_rename", "b.png.hfrec.json", "exists",
                "c.png.hfrec.json", "not_a_record", "e.png.hfrec.json", "not_a_record", "d.png.hfrec.json", "would_rename"))
                && Files.exists(mig.resolve("a.png.hfrec.json")) && !Files.exists(mig.resolve("a.png.hfrec")));
        long renamed = ProcessingRecord.migrateFolder(mig, true).stream().filter(row -> "renamed".equals(row[2])).count();
        expect("migrate renames the two records, subfolder included, and nothing else", renamed == 2
                && Files.isRegularFile(mig.resolve("a.png.hfrec")) && !Files.exists(mig.resolve("a.png.hfrec.json"))
                && Files.isRegularFile(mig.resolve("sub/d.png.hfrec")) && !Files.exists(mig.resolve("sub/d.png.hfrec.json"))
                && Files.readString(mig.resolve("b.png.hfrec")).equals("taken") && Files.exists(mig.resolve("b.png.hfrec.json"))
                && Files.exists(mig.resolve("c.png.hfrec.json")) && !Files.exists(mig.resolve("c.png.hfrec"))
                && java.util.Arrays.equals(legacyBytes, Files.readAllBytes(mig.resolve("a.png.hfrec"))));

        // The preview: the sidecar's small picture, last in the file, never in the report or an embedded copy.
        java.awt.image.BufferedImage smooth = new java.awt.image.BufferedImage(1920, 1080, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 1080; y++)
            for (int x = 0; x < 1920; x++)
                smooth.setRGB(x, y, (x * 255 / 1919) << 16 | (y < 540 ? 0x2000 : 0xE000) | 128); // a ramp over two bands: a small PNG
        JSONObject pv = ProcessingRecord.preview(smooth, null);
        expect("a smooth 1920x1080 frame previews as a 320x180 PNG with no frame index: " + (pv == null ? null : pv.getString("media_type")
                + " " + pv.getInt("width") + "x" + pv.getInt("height") + " " + pv.getString("data").length()), pv != null
                && "image/png".equals(pv.getString("media_type")) && pv.getInt("width") == 320 && pv.getInt("height") == 180 && !pv.has("frame"));
        java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(pv.getString("data"))));
        expect("its data decodes to an image of the stated size", back != null && back.getWidth() == 320 && back.getHeight() == 180
                && (back.getRGB(319, 0) >> 16 & 0xFF) > 240 && (back.getRGB(0, 0) >> 8 & 0xFF) < 0x30 && (back.getRGB(0, 179) >> 8 & 0xFF) > 0xD0);
        java.util.Random rnd = new java.util.Random(7);
        java.awt.image.BufferedImage noise = new java.awt.image.BufferedImage(640, 640, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 640; y++)
            for (int x = 0; x < 640; x++)
                noise.setRGB(x, y, rnd.nextInt(0x1000000));
        JSONObject pn = ProcessingRecord.preview(noise, 5);
        int noiseBytes = pn == null ? -1 : java.util.Base64.getDecoder().decode(pn.getString("data")).length;
        expect("noise, whose PNG is over 64 KB, previews as a JPEG within 64 KB with its frame index (" + noiseBytes + " bytes)", pn != null
                && "image/jpeg".equals(pn.getString("media_type")) && noiseBytes <= ProcessingRecord.PREVIEW_BYTES && noiseBytes > 0
                && pn.getInt("width") <= ProcessingRecord.PREVIEW_SIDE && pn.getInt("frame") == 5);

        JSONObject withPreview = new JSONObject(r.toString()).put("preview", pv);
        ProcessingRecord.writeJson(out.resolve("still-preview.hfrec"), withPreview); // test_hfrecord.py validates it
        String plainText = ProcessingRecord.sidecarText(r), previewText = ProcessingRecord.sidecarText(withPreview);
        java.util.Map<String, Object> parsed = ExportReport.parse(previewText);
        List<String> keys = new ArrayList<>(parsed.keySet());
        expect("the sidecar is hfrecord, report, ..., preview last: " + keys.get(0) + ", " + keys.get(1) + ", ..., " + keys.get(keys.size() - 1),
                "hfrecord".equals(keys.get(0)) && "report".equals(keys.get(1)) && "preview".equals(keys.get(keys.size() - 1)));
        expect("Studio's JSON parser reads the long base64 string whole",
                pv.getString("data").equals(((java.util.Map<?, ?>) parsed.get("preview")).get("data")));
        expect("the preview leaves the report unchanged", ExportReport.parse(plainText).get("report").equals(parsed.get("report"))
                && ExportReport.text(parsed, true).equals(ExportReport.text(ExportReport.parse(plainText), true)));
        expect("the sidecar text without the preview is unchanged by it", previewText.startsWith(plainText.substring(0, plainText.length() - 3)));
        expect("no embedded copy carries the preview", !ProcessingRecord.pngText(withPreview).contains("\"preview\"")
                && !ProcessingRecord.exrText(withPreview).contains("\"preview\"") && !ProcessingRecord.movieText(withPreview, true).contains("\"preview\"")
                && !ProcessingRecord.embedded(withPreview, null).has("preview"));
        Path pdir = Files.createTempDirectory("hfrec-preview");
        Path stillPng = Files.writeString(pdir.resolve("still.png"), "pixels");
        ProcessingRecord.writeJson(ProcessingRecord.sidecar(stillPng), withPreview);
        Path stillCopy = Files.writeString(pdir.resolve("still-copy.png"), "pixels");
        ProcessingRecord.copySidecar(stillPng, stillCopy);
        java.util.Map<String, Object> copied = ExportReport.parse(Files.readString(ProcessingRecord.sidecar(stillCopy)));
        List<String> copiedKeys = new ArrayList<>(copied.keySet());
        expect("copySidecar keeps the preview, last", "preview".equals(copiedKeys.get(copiedKeys.size() - 1))
                && pv.getString("data").equals(((java.util.Map<?, ?>) copied.get("preview")).get("data")));
        JSONObject prep = ProcessingRecord.prepare(session, live(), null, still, "00000000-0000-4000-8000-000000000005", false);
        Path writtenSide = ProcessingRecord.writePrepared(stillPng, prep, pv);
        expect("writePrepared puts the preview in the sidecar", writtenSide != null
                && Files.readString(writtenSide).contains("\"preview\": {\n    \"media_type\": \"image/png\""));

        java.util.Map<String, Object> mapped = ExportReport.parse(previewText);
        java.awt.image.BufferedImage shown = ProcessingRecord.previewImage(mapped, pdir.resolve("elsewhere.png.hfrec"));
        expect("Open Export Record shows the embedded preview", shown != null && shown.getWidth() == 320 && shown.getHeight() == 180);
        Path sibling = pdir.resolve("frame0001.png");
        javax.imageio.ImageIO.write(smooth, "png", sibling.toFile());
        java.util.Map<String, Object> old = ExportReport.parse(plainText); // output.file_name frame0001.png, no preview
        java.awt.image.BufferedImage fallback = ProcessingRecord.previewImage(old, ProcessingRecord.sidecar(sibling));
        expect("an old sidecar without a preview falls back to its PNG export, downscaled", fallback != null && fallback.getWidth() == 320);
        expect("an old sidecar whose export is not there shows nothing", ProcessingRecord.previewImage(old, pdir.resolve("x/frame0001.png.hfrec")) == null);
        expect("a movie sidecar without a preview shows nothing, no error",
                ProcessingRecord.previewImage(ExportReport.parse(ProcessingRecord.sidecarText(m)), pdir.resolve("movie.mp4.hfrec")) == null);

        // The raw frames ffmpeg reads: rgb24 and rgb48le, top row first; the middle frame, box-averaged.
        Path raw = pdir.resolve("dump");
        byte[] dump = new byte[3 * 3 * 4 * 2];
        for (int fr = 0; fr < 3; fr++)
            for (int px = 0; px < 8; px++) {
                int o = (fr * 8 + px) * 3;
                dump[o] = (byte) (fr == 1 ? 200 : 10);
                dump[o + 1] = (byte) (px < 4 ? 50 : 150); // top row 50, bottom row 150
                dump[o + 2] = (byte) 255;
            }
        Files.write(raw, dump);
        java.awt.image.BufferedImage f1 = ExportWriter.rawFrame(raw, 4, 2, 3, 1, 320);
        expect("rawFrame reads frame 1 of an rgb24 dump, top row first", f1.getWidth() == 4 && f1.getHeight() == 2
                && f1.getRGB(0, 0) == (0xFF000000 | 200 << 16 | 50 << 8 | 255) && f1.getRGB(3, 1) == (0xFF000000 | 200 << 16 | 150 << 8 | 255));
        java.awt.image.BufferedImage avg = ExportWriter.rawFrame(raw, 4, 2, 3, 1, 2);
        expect("and box-averages it down to the side asked for", avg.getWidth() == 2 && avg.getHeight() == 1
                && avg.getRGB(1, 0) == (0xFF000000 | 200 << 16 | 100 << 8 | 255));
        byte[] deep = new byte[6 * 2 * 1 * 2];
        for (int fr = 0; fr < 2; fr++)
            for (int px = 0; px < 2; px++) {
                int o = (fr * 2 + px) * 6;
                deep[o] = 1; deep[o + 1] = (byte) (fr == 1 ? 0xC0 : 0); // little-endian: the high byte second
                deep[o + 3] = (byte) 0x80;
                deep[o + 5] = (byte) 0xFF;
            }
        Files.write(raw, deep);
        java.awt.image.BufferedImage d1 = ExportWriter.rawFrame(raw, 2, 1, 6, 1, 320);
        expect("rawFrame reads rgb48le by each sample's high byte", d1.getRGB(1, 0) == (0xFF000000 | 0xC0 << 16 | 0x80 << 8 | 0xFF));

        if (failures != 0) {
            System.out.println(failures + " processing record failure(s)");
            System.exit(1);
        }
    }
}
