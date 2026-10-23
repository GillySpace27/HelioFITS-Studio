package org.helioviewer.jhv.movie;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.helioviewer.jhv.app.AppInfo;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * An export says what made it, and a PNG carries its scene back in.
 *
 * <p>Pins the parts of Provenance that need no window: the tEXt chunk (written just before IEND,
 * ASCII only, CRC over type and data, read back only when the CRC holds), the frame block ExrCapture
 * used to build inline, the session block with the home directory written as "~" and put back on
 * the way in, the size cap on a movie comment, and the citation and credit wording. The live halves
 * (State.snapshot, GLRenderer) need the window; HS-5's verification exercises them by exporting
 * from the app.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.movie.ProvenanceCheck
 */
public final class ProvenanceCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        pngChunk(Files.createTempDirectory("hfs-provenance"));
        frameBlock();
        session();
        metadataCap();
        wording();
        if (failures != 0) {
            System.out.println(failures + " provenance failure(s)");
            System.exit(1);
        }
    }

    private static void pngChunk(Path dir) throws Exception {
        Path png = dir.resolve("frame0001.png");
        BufferedImage image = new BufferedImage(5, 3, BufferedImage.TYPE_INT_RGB);
        image.setRGB(2, 1, 0x3366cc);
        ImageIO.write(image, "png", png.toFile());
        expect("a plain PNG carries no provenance", Provenance.readPngChunk(png) == null);

        // Latin-1 and beyond: a tEXt chunk cannot hold these raw, so they travel as \\u escapes.
        JSONObject json = new JSONObject().put("writer", "HelioFITS Studio test").put("text", "D☉ and å and 🌞");
        Provenance.writePngChunk(png, json);
        JSONObject back = Provenance.readPngChunk(png);
        expect("the chunk reads back as the JSON written", back != null && json.similar(back));

        List<String> types = new ArrayList<>();
        List<byte[]> texts = new ArrayList<>();
        expect("every chunk's CRC is valid (type and data, java.util.zip.CRC32)", chunks(png, types, texts));
        expect("the tEXt sits just before IEND, and IEND is last: " + types,
                types.size() >= 3 && "IEND".equals(types.get(types.size() - 1)) && "tEXt".equals(types.get(types.size() - 2)));
        expect("the container is tEXt, not iTXt (decision A3): " + types, types.contains("tEXt") && !types.contains("iTXt"));
        byte[] body = texts.isEmpty() ? new byte[0] : texts.get(texts.size() - 1);
        boolean ascii = body.length > 0;
        for (byte b : body)
            ascii &= b >= 0x20 && b <= 0x7E || b == 0; // the one NUL is the keyword's end
        expect("the chunk holds printable ASCII only, keyword hfstudio then NUL then JSON",
                ascii && new String(body, StandardCharsets.ISO_8859_1).startsWith("hfstudio\0{"));

        BufferedImage reread = ImageIO.read(png.toFile());
        expect("the picture still opens and its pixels are unchanged",
                reread != null && reread.getWidth() == 5 && (reread.getRGB(2, 1) & 0xFFFFFF) == 0x3366cc);

        // A second stamp on the same file keeps the file valid, with IEND still last.
        Provenance.writePngChunk(png, new JSONObject().put("writer", "second"));
        List<String> twice = new ArrayList<>();
        boolean valid = chunks(png, twice, new ArrayList<>());
        expect("a second chunk keeps every CRC valid and IEND last: " + twice,
                valid && "IEND".equals(twice.get(twice.size() - 1)));

        Path text = dir.resolve("not-a.png");
        Files.writeString(text, "not a png");
        expect("a file that is not a PNG reads as no provenance", Provenance.readPngChunk(text) == null);
        boolean refused = false;
        try {
            Provenance.writePngChunk(text, json);
        } catch (IOException e) {
            refused = true;
        }
        expect("writing into a file that does not end in IEND is refused and leaves it alone",
                refused && "not a png".equals(Files.readString(text)));
    }

    /** Walks the chunks independently of Provenance and checks every CRC; collects each tEXt body. */
    private static boolean chunks(Path png, List<String> types, List<byte[]> texts) throws IOException {
        boolean ok = true;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(png))) {
            in.skipNBytes(8);
            while (true) {
                int length = in.readInt();
                byte[] type = in.readNBytes(4);
                byte[] data = in.readNBytes(length);
                int stored = in.readInt();
                CRC32 crc = new CRC32();
                crc.update(type);
                crc.update(data);
                ok &= (int) crc.getValue() == stored;
                String name = new String(type, StandardCharsets.ISO_8859_1);
                types.add(name);
                if ("tEXt".equals(name))
                    texts.add(data);
                if ("IEND".equals(name))
                    return ok;
            }
        }
    }

    /**
     * The frame block as ExrCapture.frame built it inline before the move (ExrCapture.java:111-130
     * at 7671c40), restated with fixed inputs: the move must not change a key or a value.
     */
    private static void frameBlock() {
        JSONObject expected = new JSONObject()
                .put("writer", AppInfo.stamp()) // was programName + version.revision, which never moved between releases
                .put("frame", 7)
                .put("time", "2026-04-25T00:16:00.000")
                .put("projection", "Orthographic")
                .put("gridType", "Viewpoint")
                .put("viewpoint", new JSONObject()
                        .put("location", "Earth")
                        .put("lonDeg", 1.5)
                        .put("latDeg", -7.25)
                        .put("distanceRsun", 215.0))
                .put("cameraWidth", 6.4)
                .put("viewports", 1)
                .put("whiteBackground", false)
                .put("alpha", "premultiplied")
                .put("colorspace", "R,G,B and overlay colours are linear (sRGB EOTF applied to the display-referred render); .Y and .V are data, untouched");
        JSONObject plain = Provenance.frame(7, false, "2026-04-25T00:16:00.000", "Earth", 1.5, -7.25, 215.0,
                "Orthographic", "Viewpoint", 6.4, 1, null);
        expect("the frame block is the one ExrCapture wrote, key for key", expected.similar(plain));

        JSONObject warped = Provenance.frame(7, false, "2026-04-25T00:16:00.000", "Earth", 1.5, -7.25, 215.0,
                "HelioradialUnrolled", "Viewpoint", 6.4, 1, new double[]{0.5, 0, 30});
        expect("a helioradial frame adds the three warp keys",
                warped.getDouble("warpLambda") == 0.5 && warped.getDouble("warpOuterRadiusRsun") == 0
                        && warped.getDouble("warpFieldRadiusRsun") == 30);
        JSONObject nowhere = Provenance.frame(7, true, "t", null, 0, 0, 1, "Orthographic", "Viewpoint", JSONObject.NULL, 0, null);
        expect("no location and no viewport: no location key and a null camera width, as before",
                !nowhere.getJSONObject("viewpoint").has("location") && nowhere.isNull("cameraWidth")
                        && nowhere.getBoolean("whiteBackground"));
    }

    private static void session() {
        String home = "/Users/someone";
        JSONObject scene = new JSONObject().put("org.helioviewer.jhv.state", new JSONObject()
                .put("imageLayers", new JSONArray()
                        .put(new JSONObject().put("name", "PUNCH CAM").put("data", new JSONObject()
                                .put("uris", new JSONArray()
                                        .put("https://example.org/punch/3/CAM/x.fits")
                                        .put("file:" + home + "/HFStudio/FileCache/x_1a2b3c4d5e6f.fits"))
                                .put("filter", "RHEF")))
                        .put(new JSONObject().put("name", "AIA 171").put("data", new JSONObject()
                                .put("APIRequest", new JSONObject().put("server", "IAS").put("sourceId", 10))
                                .put("filter", "None"))))
                .put("recording", new JSONObject().put("dir", home + "/HFStudio/Exports"))
                .put("other", "file:///Users/someoneelse/x.fits")
                .put("web", "https://example.org/~user/x"));
        JSONObject s = Provenance.session(scene, home, Instant.parse("2026-10-01T12:34:56.789Z"));
        expect("session keys: writer, buildId, commit, dirty, created, scene, sources, caveat",
                s.has("writer") && s.has("buildId") && s.has("commit") && s.has("dirty") && s.has("created")
                        && s.has("scene") && s.has("sources") && s.has("caveat"));
        expect("created is UTC to the second", "2026-10-01T12:34:56Z".equals(s.getString("created")));
        String text = s.toString();
        expect("the home directory appears nowhere", !text.contains(home + "/") && !text.contains('"' + home + '"'));
        expect("a sibling path that only starts the same way is left alone", text.contains("/Users/someoneelse/x.fits"));
        expect("a ~ inside a web address is left alone", text.contains("https://example.org/~user/x"));
        JSONArray sources = s.getJSONArray("sources");
        expect("one source per image layer", sources.length() == 2);
        expect("archive URIs only: the cache file is not a source",
                sources.getJSONObject(0).getJSONArray("uris").length() == 1
                        && sources.getJSONObject(0).getJSONArray("uris").getString(0).startsWith("https://"));
        expect("a server request is the source of a layer with no URIs", sources.getJSONObject(1).has("request"));
        expect("RHEF on any layer adds the caveat", "not a calibrated radiance".equals(s.getString("caveat")));

        JSONObject back = Provenance.scene(s, home);
        expect("the scene comes back with the home directory restored, equal to the original", back != null && scene.similar(back));

        JSONObject plain = new JSONObject(scene.toString());
        plain.getJSONObject("org.helioviewer.jhv.state").getJSONArray("imageLayers").getJSONObject(0)
                .getJSONObject("data").put("filter", "None");
        expect("no RHEF, no caveat", !Provenance.session(plain, home, Instant.EPOCH).has("caveat"));

        // A layer from local files (the Heliad tab, a drop): each file by its identity, never its path.
        String lib = "file:" + home + "/HeliadLibrary/store/aia/";
        JSONObject local = new JSONObject().put("org.helioviewer.jhv.state", new JSONObject()
                .put("imageLayers", new JSONArray().put(new JSONObject().put("name", "SDO AIA 193 L1").put("data", new JSONObject()
                        .put("uris", new JSONArray().put(lib + "a1.fits").put(lib + "a2.fits").put("file:" + home + "/loose.fits"))
                        .put("filter", "None")))));
        org.helioviewer.jhv.io.HeliadLibrary.Identity heliad = new org.helioviewer.jhv.io.HeliadLibrary.Identity(
                "1".repeat(64), "sdo/aia/-/-/1/193", "sdo/aia/-/-/1/193|2025-09-21T16:00:04.840", "ds-7f3a", null, 10, null, null);
        JSONObject ls = Provenance.session(local, home, Instant.EPOCH, uri -> {
            String u = uri.toString();
            if (u.endsWith("a1.fits"))
                return new org.helioviewer.jhv.io.SourceIdentity.Entry("1".repeat(64), 10, heliad);
            if (u.endsWith("loose.fits"))
                return new org.helioviewer.jhv.io.SourceIdentity.Entry("2".repeat(64), 10, null);
            return null; // a2: still being hashed
        });
        JSONObject src = ls.getJSONArray("sources").getJSONObject(0);
        JSONArray files = src.optJSONArray("files");
        expect("local files are sources again: one entry per identified file", files != null && files.length() == 2);
        expect("a library file carries sha256, dataset_key, obs_key and dataset_uid",
                files != null && "sdo/aia/-/-/1/193".equals(files.getJSONObject(0).optString("dataset_key"))
                        && files.getJSONObject(0).has("obs_key") && "ds-7f3a".equals(files.getJSONObject(0).optString("dataset_uid"))
                        && "1".repeat(64).equals(files.getJSONObject(0).optString("sha256")));
        expect("a file outside the library carries sha256 only",
                files != null && files.getJSONObject(1).keySet().equals(java.util.Set.of("sha256")));
        expect("a file not identified yet is counted, never guessed", src.optInt("files_pending") == 1);
        expect("still no local address among the uris", src.getJSONArray("uris").isEmpty());
        String localSources = ls.getJSONArray("sources").toString();
        expect("no path in the sources: " + localSources, !localSources.contains(home) && !localSources.contains("HeliadLibrary") && !localSources.contains("file:"));
        expect("the citation names the files by hash", Provenance.citation(ls).contains("2 local files, sha256 " + "1".repeat(64)));
    }

    private static void metadataCap() {
        JSONObject big = new JSONObject().put("writer", "w").put("buildId", "b").put("sources", new JSONArray().put("s"))
                .put("scene", new JSONObject().put("padding", "x".repeat(400)));
        expect("under the cap the comment is the whole session",
                new JSONObject(Provenance.metadataComment(big, 10_000)).has("scene"));
        String capped = Provenance.metadataComment(big, 200);
        expect("over the cap the scene is left out and the sources kept",
                capped.length() <= 200 && !new JSONObject(capped).has("scene") && new JSONObject(capped).has("sources"));
        String tiny = Provenance.metadataComment(big, 10);
        expect("far over the cap the build alone is written",
                !new JSONObject(tiny).has("sources") && new JSONObject(tiny).has("buildId"));
    }

    private static void wording() throws IOException {
        JSONObject s = new JSONObject().put("buildId", "0.8.4 (r1, 0123456789ab)").put("commit", "0123456789ab")
                .put("created", "2026-10-01T00:00:00Z")
                .put("sources", new JSONArray().put(new JSONObject().put("layer", "PUNCH CAM")
                        .put("uris", new JSONArray().put("https://example.org/a.fits").put("https://example.org/b.fits"))))
                .put("caveat", "not a calibrated radiance")
                .put("scene", new JSONObject().put("big", true));
        String c = Provenance.citation(s);
        String jhvDoi = "https://doi.org/10.1051/0004-6361/201730893";
        Path readme = Path.of("README.md");
        expect("the JHelioviewer DOI cited is the one README.md gives",
                Files.isRegularFile(readme) && Files.readString(readme).contains(jhvDoi) && c.contains(jhvDoi));
        expect("RHEF is cited by its DOI, with its caveat",
                c.contains("https://doi.org/10.1007/s11207-025-02578-x") && c.contains("not a calibrated radiance"));
        expect("the build and the data are named",
                c.contains("0.8.4 (r1, 0123456789ab)") && c.contains("PUNCH CAM: https://example.org/a.fits and 1 more"));
        expect("the citation block leaves the scene out", !c.contains("\"big\""));
        expect("credit line with a layer and RHEF",
                "Made with HelioFITS Studio | PUNCH CAM | RHEF: not a calibrated radiance".equals(Provenance.creditLine("PUNCH CAM", true)));
        expect("credit line with neither", "Made with HelioFITS Studio".equals(Provenance.creditLine("", false)));
    }

}
