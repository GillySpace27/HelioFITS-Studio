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

    private static java.awt.image.BufferedImage flat(int w, int h, int rgb) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                img.setRGB(x, y, rgb);
        return img;
    }

    private static int previewRgb(JSONObject pv) throws java.io.IOException {
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(pv.getString("data"))));
        return img.getRGB(img.getWidth() / 2, img.getHeight() / 2) & 0xFFFFFF;
    }

    private static boolean near(int rgb, int want) {
        for (int sh = 0; sh <= 16; sh += 8)
            if (Math.abs((rgb >> sh & 0xFF) - (want >> sh & 0xFF)) > 24)
                return false;
        return true;
    }

    /** The record of {@code side} as parsed, its keys in order. */
    private static List<String> keys(Path side) throws java.io.IOException {
        return new ArrayList<>(ExportReport.parse(Files.readString(side)).keySet());
    }

    /**
     * Adding a preview to a sidecar written before there were any: only the preview is added, last,
     * every other byte the same; nothing is written when no preview can be made.
     */
    private static void backfill(String plainText, JSONObject r) throws Exception {
        Path dir = Files.createTempDirectory("hfrec-backfill");
        // a still: plainText names frame0001.png and has no preview
        javax.imageio.ImageIO.write(flat(1600, 800, 0x3366CC), "png", dir.resolve("frame0001.png").toFile());
        Path side = Files.writeString(dir.resolve("frame0001.png.hfrec"), plainText);
        List<String> before = keys(side);
        expect("addPreview writes a still's sidecar that has none", ProcessingRecord.addPreview(side, null));
        String after = Files.readString(side);
        expect("every byte before the closing brace is the same, the file ends as it did",
                after.startsWith(plainText.substring(0, plainText.length() - 3)) && after.endsWith("\n  }\n}\n"));
        List<String> now = keys(side);
        expect("the keys are the same, in the same order, with preview added last: " + now.get(now.size() - 1),
                now.subList(0, now.size() - 1).equals(before) && "preview".equals(now.get(now.size() - 1)));
        JSONObject was = new JSONObject(plainText), is = new JSONObject(after);
        boolean same = true;
        for (String k : was.keySet())
            same &= was.get(k) instanceof JSONObject o ? o.similar(is.get(k)) : was.get(k) instanceof JSONArray a ? a.similar(is.get(k)) : was.get(k).equals(is.get(k));
        expect("report and every other value unchanged", same && was.getJSONArray("report").similar(is.getJSONArray("report")));
        JSONObject pv = is.getJSONObject("preview");
        expect("the preview is the export at 320x160, by the export encoder: " + pv.optString("media_type"), "image/png".equals(pv.getString("media_type"))
                && pv.getInt("width") == 320 && pv.getInt("height") == 160 && !pv.has("frame") && near(previewRgb(pv), 0x3366CC));
        expect("and its text is what a new sidecar writes", after.equals(ProcessingRecord.sidecarText(new JSONObject(plainText).put("preview", pv))));
        byte[] once = Files.readAllBytes(side);
        expect("a sidecar that has a preview is not written again", !ProcessingRecord.addPreview(side, null)
                && java.util.Arrays.equals(once, Files.readAllBytes(side)));

        // any formatting survives: numbers as written, other indentation, no final newline
        String odd = "{\"summary\":\"x\",\"hfrecord\":\"1.0\",\n\t\"output\": {\"kind\": \"still\", \"file_name\": \"odd.jpg\", \"fps\": 1.50, \"e\": 2.0E3},  \"z\": [1, 2.000]   }";
        javax.imageio.ImageIO.write(flat(100, 50, 0xCC3300), "jpeg", dir.resolve("odd.jpg").toFile());
        Path oddSide = Files.writeString(dir.resolve("odd.jpg.hfrec"), odd);
        expect("a JPEG still gains a preview", ProcessingRecord.addPreview(oddSide, null));
        String oddAfter = Files.readString(oddSide);
        int cut = odd.lastIndexOf("]") + 1;
        expect("an oddly formatted record keeps every byte, 1.50 and 2.0E3 included", oddAfter.startsWith(odd.substring(0, cut))
                && oddAfter.endsWith(odd.substring(cut)) && oddAfter.substring(cut).startsWith(",\n  \"preview\": {")
                && new JSONObject(oddAfter).getJSONObject("preview").getInt("width") == 100);

        // nothing to make one from: the file is not touched
        Path lone = Files.writeString(dir.resolve("gone.png.hfrec"), plainText.replace("frame0001.png", "gone.png"));
        byte[] loneBytes = Files.readAllBytes(lone);
        java.nio.file.attribute.FileTime loneTime = Files.getLastModifiedTime(lone);
        expect("a sidecar with no export beside it is left byte for byte, not rewritten", !ProcessingRecord.addPreview(lone, null)
                && java.util.Arrays.equals(loneBytes, Files.readAllBytes(lone)) && loneTime.equals(Files.getLastModifiedTime(lone))
                && !Files.exists(dir.resolve("gone.png.hfrec.tmp")));
        Files.writeString(dir.resolve("cube.exr"), "exr");
        Path exr = Files.writeString(dir.resolve("cube.exr.hfrec"), "{\"hfrecord\": \"1.0\", \"output\": {\"kind\": \"exr_series\", \"file_name\": \"cube.exr\"}}\n");
        Files.writeString(dir.resolve("bad.png"), "not a png");
        Path bad = Files.writeString(dir.resolve("bad.png.hfrec"), plainText.replace("frame0001.png", "bad.png"));
        byte[] badBytes = Files.readAllBytes(bad);
        expect("EXR and an unreadable PNG get none, untouched", !ProcessingRecord.addPreview(exr, null) && !ProcessingRecord.addPreview(bad, null)
                && java.util.Arrays.equals(badBytes, Files.readAllBytes(bad)));

        // a PNG frame series: its middle file
        Path series = Files.createDirectories(dir.resolve("series"));
        int[] colours = {0xFF0000, 0x00FF00, 0x0000FF};
        for (int i = 0; i < 3; i++)
            javax.imageio.ImageIO.write(flat(64, 64, colours[i]), "png", series.resolve(String.format("frame%04d.png", i + 1)).toFile());
        Path seriesSide = Files.writeString(dir.resolve("series.hfrec"),
                "{\n  \"hfrecord\": \"1.0\",\n  \"output\": {\"kind\": \"frame_series\", \"file_name\": \"series\"}\n}\n");
        expect("a frame series gains its middle file, frame 1", ProcessingRecord.addPreview(seriesSide, null)
                && new JSONObject(Files.readString(seriesSide)).getJSONObject("preview").getInt("frame") == 1
                && near(previewRgb(new JSONObject(Files.readString(seriesSide)).getJSONObject("preview")), 0x00FF00));

        // a movie: the middle frame through ffmpeg; without one, nothing
        Path movieSide = Files.writeString(dir.resolve("clip.mp4.hfrec"),
                "{\n  \"hfrecord\": \"1.0\",\n  \"output\": {\"kind\": \"movie\", \"file_name\": \"clip.mp4\", \"frames\": 5, \"fps\": 10}\n}\n");
        Files.writeString(dir.resolve("clip.mp4"), "movie");
        byte[] movieBytes = Files.readAllBytes(movieSide);
        expect("a movie with no ffmpeg reachable gets none, untouched", !ProcessingRecord.addPreview(movieSide, null)
                && java.util.Arrays.equals(movieBytes, Files.readAllBytes(movieSide)));
        Path ffmpeg = null;
        for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (!d.isEmpty() && Files.isExecutable(Path.of(d, "ffmpeg")))
                ffmpeg = ffmpeg == null ? Path.of(d, "ffmpeg") : ffmpeg;
        if (ffmpeg == null)
            System.out.println("  skip movie preview through ffmpeg: no ffmpeg on PATH");
        else {
            int[] five = {0xFF0000, 0xFFFF00, 0x0000FF, 0x00FFFF, 0xFF00FF};
            for (int i = 0; i < 5; i++)
                javax.imageio.ImageIO.write(flat(64, 64, five[i]), "png", dir.resolve(String.format("m%d.png", i)).toFile());
            Files.delete(dir.resolve("clip.mp4"));
            Process enc = new ProcessBuilder(ffmpeg.toString(), "-nostdin", "-v", "error", "-framerate", "10", "-i", dir.resolve("m%d.png").toString(),
                    "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p", dir.resolve("clip.mp4").toString()).redirectErrorStream(true).start();
            enc.getInputStream().readAllBytes();
            expect("ffmpeg made the test movie", enc.waitFor() == 0);
            expect("a movie gains its middle frame, frame 2, through ffmpeg", ProcessingRecord.addPreview(movieSide, ffmpeg)
                    && new JSONObject(Files.readString(movieSide)).getJSONObject("preview").getInt("frame") == 2
                    && near(previewRgb(new JSONObject(Files.readString(movieSide)).getJSONObject("preview")), 0x0000FF));
        }

        // (a) a rename on read adds one too; (b) Upgrade Sidecars in Folder renames and adds, and says so per file
        Path legacy = Files.writeString(dir.resolve("frame0002.png.hfrec.json"), plainText.replace("frame0001.png", "frame0002.png"));
        Files.copy(dir.resolve("frame0001.png"), dir.resolve("frame0002.png"));
        Path renamed = ProcessingRecord.migrateLegacy(legacy);
        expect("an older sidecar renamed on read gains a preview", renamed.getFileName().toString().equals("frame0002.png.hfrec")
                && new JSONObject(Files.readString(renamed)).has("preview"));
        Path up = Files.createTempDirectory("hfrec-upgrade");
        Files.copy(dir.resolve("frame0001.png"), up.resolve("f.png"));
        Files.copy(dir.resolve("frame0001.png"), up.resolve("g.png"));
        Files.writeString(up.resolve("f.png.hfrec.json"), plainText.replace("frame0001.png", "f.png"));
        Files.writeString(up.resolve("g.png.hfrec"), plainText.replace("frame0001.png", "g.png"));
        Path h = Files.writeString(up.resolve("h.png.hfrec"), plainText.replace("frame0001.png", "h.png"));
        byte[] hBytes = Files.readAllBytes(h);
        java.util.Map<String, String> plan = new java.util.TreeMap<>(), done = new java.util.TreeMap<>();
        for (String[] row : ProcessingRecord.migrateFolder(up, false))
            plan.put(Path.of(row[0]).getFileName().toString(), row[2] + "/" + row[3]);
        expect("upgrade plan: " + plan, plan.equals(java.util.Map.of("f.png.hfrec.json", "would_rename/missing", "g.png.hfrec", "current/missing",
                "h.png.hfrec", "current/missing")) && !new JSONObject(Files.readString(up.resolve("g.png.hfrec"))).has("preview"));
        for (String[] row : ProcessingRecord.migrateFolder(up, true))
            done.put(Path.of(row[0]).getFileName().toString(), row[2] + "/" + row[3]);
        expect("upgrade: " + done, done.equals(java.util.Map.of("f.png.hfrec.json", "renamed/added", "g.png.hfrec", "current/added",
                "h.png.hfrec", "current/none")) && new JSONObject(Files.readString(up.resolve("f.png.hfrec"))).has("preview")
                && java.util.Arrays.equals(hBytes, Files.readAllBytes(h)));
    }
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
                .put("frame", Provenance.frame(0, false, "2025-09-21T20:32:00.000", "Earth", 0, 0, 215, "Orthographic", "Viewpoint", 6.4, 1, null))
                .put("environment", new JSONObject()
                        .put("os", new JSONObject().put("name", "Mac OS X").put("version", "15.1").put("arch", "aarch64"))
                        .put("jvm", new JSONObject().put("version", "25").put("vendor", "Eclipse Adoptium"))
                        .put("gpu", new JSONObject().put("vendor", "Apple").put("renderer", "ANGLE (Apple M3)").put("version", "OpenGL ES 3.0").put("glsl", "300"))
                        .put("display", new JSONObject().put("dither_enabled", true).put("deep_canvas", false).put("edr_canvas", false))
                        .put("core", new JSONObject().put("commit", "864b2362b195bf453ae8982c854053af1d4b8f44")));
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

    private static String sha256Hex(byte[]... parts) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        for (byte[] p : parts)
            md.update(p);
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    /** hfrecord 1.1: environment, reproducibility, frame_map, and the pixel and content digests. */
    private static void reproducibility(Path out, JSONObject session, JSONObject mSession, ProcessingRecord.Output still,
                                        ProcessingRecord.Output movie) throws Exception {
        JSONObject r = ProcessingRecord.build(session, live(), null, still, "frame0001.png", "a".repeat(64), HOME,
                ProcessingRecordCheck::identify, "00000000-0000-4000-8000-000000000011");
        JSONObject env = r.getJSONObject("environment");
        expect("the environment block is in the record: os, jvm, gpu, display, core", env.keySet().containsAll(List.of("os", "jvm", "gpu", "display", "core")));
        expect("a 16-bit output skips the dither and says the capture was high bit depth",
                !env.getJSONObject("display").getBoolean("dither") && env.getJSONObject("display").getBoolean("high_bit_depth_capture")
                        && env.getJSONObject("display").keySet().equals(java.util.Set.of("dither", "high_bit_depth_capture")));
        JSONObject m8 = ProcessingRecord.build(mSession, live(), null, movie, "movie.mp4", null, HOME,
                ProcessingRecordCheck::identify, "00000000-0000-4000-8000-000000000012");
        expect("an 8-bit output dithers, and the verdict names it",
                m8.getJSONObject("environment").getJSONObject("display").getBoolean("dither") && m8.getJSONObject("reproducibility").getJSONArray("reasons")
                        .toList().stream().anyMatch(s -> s.toString().startsWith("The shader dithered")));
        expect("the core commit is in environment.core", "864b2362b195bf453ae8982c854053af1d4b8f44".equals(env.getJSONObject("core").getString("commit")));
        String user = System.getProperty("user.name", "");
        expect("the environment carries no user name and no host name",
                (user.length() < 3 || !env.toString().contains(user)) && !env.toString().toLowerCase().contains("hostname"));
        JSONObject noEnv = live();
        noEnv.remove("environment");
        JSONObject old = ProcessingRecord.build(session, noEnv, null, still, "f.png", null, HOME, ProcessingRecordCheck::identify,
                "00000000-0000-4000-8000-000000000013");
        expect("a build with no environment block says so and writes none", !old.has("environment")
                && old.getJSONObject("reproducibility").getJSONArray("reasons").toList().stream().anyMatch(s -> s.toString().startsWith("No environment block")));

        JSONObject repro = r.getJSONObject("reproducibility");
        List<Object> reasons = repro.getJSONArray("reasons").toList();
        expect("a Helioviewer layer makes an exact replay impossible, and the reason names the layer",
                !repro.getBoolean("exact_replay_possible") && reasons.stream().anyMatch(s -> s.toString().contains("AIA 171") && s.toString().contains("Helioviewer")));
        JSONObject cleanSession = Provenance.session(scene(0.45, false), HOME, Instant.parse("2026-10-23T00:00:00Z"), u -> null);
        JSONObject oneLayerScene = scene(0.45, false);
        oneLayerScene.getJSONObject("org.helioviewer.jhv.state").getJSONArray("imageLayers").remove(1);
        oneLayerScene.getJSONObject("org.helioviewer.jhv.state").remove("compare");
        JSONObject clean = ProcessingRecord.build(Provenance.session(oneLayerScene, HOME, Instant.parse("2026-10-23T00:00:00Z"), u -> null),
                live(), null, still, "f.png", null, HOME, ProcessingRecordCheck::identify, "00000000-0000-4000-8000-000000000014");
        boolean dirty = clean.getJSONObject("producer").getBoolean("dirty");
        expect("two identified library files, one layer, a known environment: exact replay possible unless the build is dirty: "
                        + clean.getJSONObject("reproducibility").getJSONArray("reasons"),
                clean.getJSONObject("reproducibility").getBoolean("exact_replay_possible") == !dirty);

        JSONObject starScene = scene(0.45, false);
        starScene.getJSONObject("org.helioviewer.jhv.state").getJSONArray("imageLayers").remove(1);
        starScene.getJSONObject("org.helioviewer.jhv.state").remove("compare");
        starScene.getJSONObject("org.helioviewer.jhv.state").put("layers", new JSONArray()
                .put(new JSONObject().put("className", "org.helioviewer.jhv.layers.StarLayer").put("name", "Stars").put("enabled", true))
                .put(new JSONObject().put("className", "org.helioviewer.jhv.layers.GridLayer").put("name", "Grid").put("enabled", true)));
        JSONObject stars = ProcessingRecord.build(Provenance.session(starScene, HOME, Instant.parse("2026-10-23T00:00:00Z"), u -> null),
                live(), null, still, "f.png", null, HOME, ProcessingRecordCheck::identify, "00000000-0000-4000-8000-000000000022");
        List<Object> starReasons = stars.getJSONObject("reproducibility").getJSONArray("reasons").toList();
        JSONObject starIn = stars.getJSONArray("inputs_external").getJSONObject(0);
        expect("a Stars overlay is an unpinned star_catalog input when its file could not be hashed, a Grid is not an input: " + starReasons,
                stars.getJSONArray("inputs_external").length() == 1 && "star_catalog".equals(starIn.getString("kind")) && !starIn.getBoolean("pinned")
                        && starIn.isNull("sha256") && starReasons.stream().anyMatch(s -> s.toString().contains("hipparcos")));
        JSONObject starLive = live().put("star_catalog_sha256", "5".repeat(64));
        JSONObject starPinned = ProcessingRecord.build(Provenance.session(starScene, HOME, Instant.parse("2026-10-23T00:00:00Z"), u -> null),
                starLive, null, still, "f.png", null, HOME, ProcessingRecordCheck::identify, "00000000-0000-4000-8000-000000000023");
        expect("hashed, the star file pins the input and adds no reason", starPinned.getJSONArray("inputs_external").getJSONObject(0).getBoolean("pinned")
                && "5".repeat(64).equals(starPinned.getJSONArray("inputs_external").getJSONObject(0).getString("sha256"))
                && starPinned.getJSONObject("reproducibility").getJSONArray("reasons").toList().stream().noneMatch(s -> s.toString().contains("hipparcos")));

        // frame_map: layer A has two sources and live frames 2; B is a Helioviewer layer; a map of the wrong length is dropped.
        String lib = "file:" + HOME + "/HelioFITSLibrary/store/aia/"; // the real URIs, home put back
        // the movie showed a2 first, then a1: the frame key is a file, and the index is where it sits in the layer's list
        JSONObject map = new JSONObject().put("times", new JSONArray().put("2025-09-21T20:00:00").put("2025-09-21T20:01:00"))
                .put("by_layer_key", new JSONObject().put(A, new JSONArray().put(lib + "a2.fits").put(lib + "a1.fits")).put(B, new JSONArray().put("jpip://x").put("jpip://x")));
        ProcessingRecord.Output two = new ProcessingRecord.Output("movie", "video/mp4", 64, 64, 2, 10, "H.264", 8, true);
        JSONObject withMap = ProcessingRecord.build(mSession, live(), null, two, "movie.mp4", null, HOME, ProcessingRecordCheck::identify,
                "00000000-0000-4000-8000-000000000015", map);
        JSONObject fm = withMap.getJSONObject("frame_map");
        expect("frame_map indexes the sources by the file shown, not by the frame number", fm.getJSONObject("by_layer").has(A)
                && fm.getJSONObject("by_layer").getJSONArray(A).getInt(0) == 1 && fm.getJSONObject("by_layer").getJSONArray(A).getInt(1) == 0
                && fm.getJSONArray("times").length() == 2);
        JSONObject unknownKey = new JSONObject(map.toString());
        unknownKey.getJSONObject("by_layer_key").put(A, new JSONArray().put(lib + "zzz.fits").put(lib + "a1.fits"));
        JSONObject dropped = ProcessingRecord.build(mSession, live(), null, two, "movie.mp4", null, HOME, ProcessingRecordCheck::identify,
                "00000000-0000-4000-8000-000000000024", unknownKey);
        expect("a frame whose file is not in the layer's list drops the layer, with a reason", !dropped.has("frame_map")
                && dropped.getJSONObject("reproducibility").getJSONArray("reasons").toList().stream().anyMatch(s -> s.toString().contains("\"SDO AIA 193")));
        expect("and leaves out the Helioviewer layer, saying why", !fm.getJSONObject("by_layer").has(B)
                && withMap.getJSONObject("reproducibility").getJSONArray("reasons").toList().stream().anyMatch(s -> s.toString().startsWith("Frame map omits layer \"AIA 171\"")));
        ProcessingRecord.Output three = new ProcessingRecord.Output("movie", "video/mp4", 64, 64, 3, 10, "H.264", 8, true);
        JSONObject shortMap = ProcessingRecord.build(mSession, live(), null, three, "movie.mp4", null, HOME, ProcessingRecordCheck::identify,
                "00000000-0000-4000-8000-000000000016", map);
        expect("a map that does not cover every output frame is dropped with a reason", !shortMap.has("frame_map")
                && shortMap.getJSONObject("reproducibility").getJSONArray("reasons").toList().stream().anyMatch(s -> s.toString().startsWith("No frame map")));
        expect("a still has no frame_map", !ProcessingRecord.build(session, live(), null, still, "f.png", null, HOME, ProcessingRecordCheck::identify,
                "00000000-0000-4000-8000-000000000017", map).has("frame_map"));
        expect("the embedded copy of a movie never holds the frame map or a digest",
                !ProcessingRecord.embedded(withMap, null).has("frame_map") && !ProcessingRecord.shortened(ProcessingRecord.embedded(withMap, null)).has("frame_map"));

        // pixel digests: hfpix/1 of whole frames of a raw dump (top row first, R G B), a partial last frame ignored
        Path dir = Files.createTempDirectory("hfrec-pixels");
        byte[] raw = new byte[3 * 24 + 5]; // three 4 x 2 rgb24 frames and a tail
        new java.util.Random(7).nextBytes(raw);
        Path dump = Files.write(dir.resolve("dump"), raw);
        ProcessingRecord.Pixels px = ProcessingRecord.Pixels.of(dump, 4, 2, 3, null);
        expect("Pixels: one digest per whole frame, a partial tail ignored", px.frames().size() == 3);
        expect("Pixels: frame 1 is the hfpix/1 digest of its 24 bytes",
                px.frames().get(1).equals(ProcessingRecord.Pixels.hfpix(4, 2, 3, 8, java.util.Arrays.copyOfRange(raw, 24, 48))));
        expect("Pixels: sha256 is the first frame's", px.sha256().equals(px.frames().get(0)));
        expect("hfpix/1 hashes the header 'hfpix/1 4 2 3 8' then the samples",
                ProcessingRecord.Pixels.hfpix(4, 2, 3, 8, new byte[24]).equals(sha256Hex("hfpix/1 4 2 3 8\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII), new byte[24])));
        // 16 bit: the dump is little-endian, hfpix/1 is big-endian
        byte[] le = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}; // two rgb48le pixels (w 2, h 1)
        byte[] be = {2, 1, 4, 3, 6, 5, 8, 7, 10, 9, 12, 11};
        expect("a 16-bit dump is swapped to big-endian before it is hashed",
                ProcessingRecord.Pixels.of(Files.write(dir.resolve("deep"), le), 2, 1, 6, null).sha256().equals(ProcessingRecord.Pixels.hfpix(2, 1, 3, 16, be)));
        JSONObject sc = ProcessingRecord.build(session, live(), null, still, "f.png", null, HOME, ProcessingRecordCheck::identify,
                "00000000-0000-4000-8000-000000000018");
        ProcessingRecord.addPixels(sc, ProcessingRecord.Pixels.of(dump, 4, 2, 3, null), null);
        expect("a still gets pixel_sha256 and no frame_digests", sc.getJSONObject("output").getString("pixel_sha256").equals(px.sha256())
                && !sc.getJSONObject("output").has("frame_digests"));
        JSONObject mv = ProcessingRecord.build(mSession, live(), null, two, "movie.mp4", null, HOME, ProcessingRecordCheck::identify,
                "00000000-0000-4000-8000-000000000019");
        ProcessingRecord.addPixels(mv, ProcessingRecord.Pixels.of(dump, 4, 2, 3, new ProcessingRecord.Encoder("ffmpeg version 9.0.1", "H.264", List.of("-c:v", "libx264"))), null);
        expect("a movie gets frame_digests, a null content_sha256 and the encoder under environment.ffmpeg", mv.getJSONObject("output").getJSONArray("frame_digests").length() == 3
                && mv.getJSONObject("output").isNull("content_sha256")
                && "ffmpeg version 9.0.1".equals(mv.getJSONObject("environment").getJSONObject("ffmpeg").getString("version"))
                && "H.264".equals(mv.getJSONObject("environment").getJSONObject("ffmpeg").getString("encoder"))
                && mv.getJSONObject("environment").getJSONObject("ffmpeg").getJSONArray("arguments").length() == 2);
        JSONObject none = ProcessingRecord.build(mSession, live(), null, two, "movie.mp4", null, HOME, ProcessingRecordCheck::identify,
                "00000000-0000-4000-8000-000000000020");
        ProcessingRecord.addPixels(none, null, null);
        expect("no digest taken: the record says exact replay cannot be checked", !none.getJSONObject("reproducibility").getBoolean("exact_replay_possible")
                && none.getJSONObject("reproducibility").getJSONArray("reasons").toList().stream().anyMatch(s -> s.toString().startsWith("No pixel digest")));
        JSONObject exr = ProcessingRecord.build(session, live(), null, new ProcessingRecord.Output("exr_series", "image/x-exr", 64, 64, 2, 10, "EXR", 16, true),
                "frame.exr", null, HOME, ProcessingRecordCheck::identify, "00000000-0000-4000-8000-000000000021");
        expect("an EXR series says at build time that no pixel digest is taken", exr.getJSONObject("reproducibility").getJSONArray("reasons").toList().stream()
                .anyMatch(s -> s.toString().startsWith("No pixel digest")));
        expect("frames_pending counts sources not identified", exr.getJSONObject("reproducibility").getInt("frames_pending") == 0);

        // content_sha256: the PNG without its text chunks
        Path png = dir.resolve("c.png");
        javax.imageio.ImageIO.write(flat(8, 4, 0x336699), "png", png.toFile());
        String plain = ProcessingRecord.pngContentSha256(png);
        Provenance.writePngChunk(png, session);
        expect("content_sha256 ignores the text chunks Studio adds (it keeps only IHDR, PLTE, tRNS, IDAT, IEND)", plain != null && plain.equals(ProcessingRecord.pngContentSha256(png)));
        javax.imageio.ImageIO.write(flat(8, 4, 0x336698), "png", png.toFile());
        expect("and changes with the picture", !plain.equals(ProcessingRecord.pngContentSha256(png)));

        // the dump ffmpeg reads is the picture it writes: raw rgb24 -> PNG -> decoded samples give the same digest
        Path ffmpeg = null;
        for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (ffmpeg == null && !d.isEmpty() && Files.isExecutable(Path.of(d, "ffmpeg")))
                ffmpeg = Path.of(d, "ffmpeg");
        if (ffmpeg == null)
            System.out.println("  skip raw dump through ffmpeg to PNG: no ffmpeg on PATH");
        else {
            byte[] frame = new byte[8 * 4 * 3];
            new java.util.Random(11).nextBytes(frame);
            Path one = Files.write(dir.resolve("one.raw"), frame);
            Path viaFfmpeg = dir.resolve("one.png");
            Process p = new ProcessBuilder(ffmpeg.toString(), "-nostdin", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "8x4",
                    "-i", one.toString(), "-vf", "scale=in_range=pc:out_range=pc", "-frames:v", "1", viaFfmpeg.toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            expect("ffmpeg wrote the PNG", p.waitFor() == 0);
            java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(viaFfmpeg.toFile());
            byte[] samples = new byte[frame.length];
            for (int y = 0, i = 0; y < 4; y++)
                for (int x = 0; x < 8; x++) {
                    int rgb = back.getRGB(x, y);
                    samples[i++] = (byte) (rgb >> 16);
                    samples[i++] = (byte) (rgb >> 8);
                    samples[i++] = (byte) rgb;
                }
            expect("the decoded PNG samples hash to the digest of the raw dump", ProcessingRecord.Pixels.of(one, 8, 4, 3, null).sha256()
                    .equals(ProcessingRecord.Pixels.hfpix(8, 4, 3, 8, samples)));
        }
        ProcessingRecord.writeJson(out.resolve("movie-map.hfrec"), withMap);
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of("extra", "test-classes", "hfrecord");
        Files.createDirectories(out);

        JSONObject session = Provenance.session(scene(0.45, false), HOME, Instant.parse("2026-10-23T00:00:00Z"), u -> null);
        ProcessingRecord.Output still = new ProcessingRecord.Output("still", "image/png", 2048, 2048, 1, null, "png", 16, true);
        JSONObject r = ProcessingRecord.build(session, live(), null, still, "frame0001.png", "a".repeat(64), HOME,
                ProcessingRecordCheck::identify, "00000000-0000-4000-8000-000000000001");
        ProcessingRecord.writeJson(out.resolve("still.hfrec"), r);

        expect("format 1.1, reader_compat 1", "1.1".equals(r.getString("hfrecord")) && r.getInt("reader_compat") == 1);
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

        // A chained filter: MGN at 60% on RHEF. Every stage, its strength, the order and the settings that move the arithmetic are in the record.
        JSONObject chained = scene(0.45, false);
        chained.getJSONObject("org.helioviewer.jhv.state").getJSONArray("imageLayers").getJSONObject(0).getJSONObject("data")
                .put("filterChain", new JSONArray().put(new JSONObject().put("type", "MGN").put("strength", 0.6)));
        JSONObject chainedSession = Provenance.session(chained, HOME, Instant.parse("2026-10-23T00:00:00Z"), u -> null);
        JSONObject cr = ProcessingRecord.build(chainedSession, live(), null, still, "frame0001.png", "a".repeat(64), HOME,
                ProcessingRecordCheck::identify, "00000000-0000-4000-8000-000000000002");
        JSONObject ca = cr.getJSONArray("layers").getJSONObject(0);
        expect("a chain writes one image_filter step per stage, in order: " + ops(ca), ops(ca).equals(List.of("select_plane", "clip", "stretch", "image_filter",
                "image_filter", "geometric_mask", "levels", "radial_gain", "difference", "sharpen", "rhef_upsilon", "colormap", "channel_mask", "opacity_blend")));
        List<JSONObject> filters = new ArrayList<>();
        for (Object o : ca.getJSONArray("steps"))
            if ("image_filter".equals(((JSONObject) o).getString("op")))
                filters.add((JSONObject) o);
        expect("stage 1 is RHEF with the chain's settings: " + filters.get(0).opt("chain"), "RHEF".equals(filters.get(0).getString("algorithm"))
                && filters.get(0).getJSONObject("chain").getInt("stages") == 2 && filters.get(0).getJSONObject("chain").getInt("max_stages") == 3
                && filters.get(0).getJSONObject("chain").getString("intermediates").equals("float32")
                && filters.get(0).getJSONObject("chain").getString("rhef_input").startsWith("float16"));
        expect("stage 2 is MGN at 0.6, index 1, with its formula: " + filters.get(1), "MGN".equals(filters.get(1).getString("algorithm"))
                && filters.get(1).getJSONObject("params").getDouble("strength") == 0.6 && filters.get(1).getJSONObject("params").getInt("chain_index") == 1
                && filters.get(1).getString("formula").contains("MGN(y_prev)"));
        expect("the RHEF stamp stays on the RHEF stage and the layer is still flagged RHEF", filters.get(0).has("stamp") && !filters.get(1).has("stamp")
                && cr.getString("summary").contains("RHEF then 60% MGN"));
        expect("a single filter has no chain block", !stepOf(a, "image_filter").has("chain"));
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
        expect("migrate plan: " + plan, plan.equals(java.util.Map.of("a.png.hfrec.json", "would_rename", "b.png.hfrec.json", "exists", "b.png.hfrec", "not_a_record",
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

        reproducibility(out, session, mSession, still, movie);

        backfill(plainText, r);

        if (failures != 0) {
            System.out.println(failures + " processing record failure(s)");
            System.exit(1);
        }
    }
}
