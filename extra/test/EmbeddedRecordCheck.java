package org.helioviewer.jhv.movie;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.helioviewer.jhv.app.Platform;
import org.helioviewer.jhv.image.nio.MappedImageFactory;
import org.helioviewer.jhv.io.Directories;
import org.helioviewer.jhv.io.FileUtils;
import org.helioviewer.jhv.io.SourceIdentity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The processing record's embedded copies (heliosoftware/spec/processing-record.md, "Where each
 * format keeps it"), beside the sidecar:
 * <ul>
 * <li>PNG: tEXt hfrecord equals the sidecar except output.sha256 (null inside), output.file_name
 * (sidecars only) and native (native_ref png:tEXt:hfstudio inside); the suite
 * HelioSoftware-Provenance, RHEF and Disclaimer chunks are there; the sidecar hashes the file
 * with every chunk in it.</li>
 * <li>MP4: a real H.264 export through the bundled ffmpeg carries the shortened record in its
 * hfrecord tag, the credit line in description and the session in comment as before, read back
 * with the same ffmpeg (and with ffprobe as well when one is on the PATH).</li>
 * </ul>
 * Embedded copies are written to extra/test-classes/hfrecord/ so test_hfrecord.py validates them.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.movie.EmbeddedRecordCheck
 */
public final class EmbeddedRecordCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final String HOME = "/Users/someone";
    private static final String A = "00000000-0000-4000-8000-00000000000a";

    private static JSONObject scene() {
        String lib = "file:" + HOME + "/HeliadLibrary/store/aia/";
        JSONObject params = new JSONObject().put("opacity", 1).put("lut", "SDO-AIA 193 \u00c5").put("clippingMode", "Percentile05")
                .put("scalingMode", "Gamma").put("gamma", 0.45).put("upsilonLow", 0.6).put("upsilonHigh", 0.4).put("differenceMode", "None");
        JSONObject a = new JSONObject().put("className", "org.helioviewer.jhv.layers.ImageLayer").put("name", "SDO AIA 193").put("id", A)
                .put("enabled", true).put("data", new JSONObject()
                        .put("uris", new JSONArray().put(lib + "a1.fits").put(lib + "a2.fits"))
                        .put("imageParams", params).put("filter", "RHEF"));
        JSONObject state = new JSONObject().put("time", "2025-09-21T20:32:00").put("imageLayers", new JSONArray().put(a));
        return new JSONObject().put("org.helioviewer.jhv.state", state);
    }

    private static SourceIdentity.Entry identify(URI uri) {
        return new SourceIdentity.Entry(uri.toString().endsWith("a1.fits") ? "1".repeat(64) : "2".repeat(64), 1000, null);
    }

    private static JSONObject live() {
        return new JSONObject().put("layers", new JSONObject().put(A, new JSONObject().put("fits", true).put("frame", 0).put("frames", 2)
                .put("clip", new JSONArray().put(12.5).put(3400.25)).put("clip_from", "decoded frame")));
    }

    /** The sidecar as its embedded copy should read: the three differences the spec allows, undone. */
    private static JSONObject asEmbedded(JSONObject sidecar, String nativeRef) {
        JSONObject c = new JSONObject(sidecar.toString());
        c.getJSONObject("output").remove("file_name");
        c.getJSONObject("output").put("sha256", JSONObject.NULL);
        c.remove("native");
        c.put("native_ref", nativeRef);
        return c;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("hfs-embedded-record"); // Directories must never reach the real ~/HFStudio
        System.setProperty("user.home", home.toString());
        // Before ExportWriter loads: its ffmpeg path is fixed from Directories.libCacheDir when it does.
        Platform.init();
        Directories.createPersistentDirs();
        Directories.createCacheDirs();
        Path out = Path.of("extra", "test-classes", "hfrecord");
        Files.createDirectories(out);

        JSONObject session = Provenance.session(scene(), HOME, Instant.parse("2026-10-23T00:00:00Z"), u -> null);
        ProcessingRecord.Output still = new ProcessingRecord.Output("still", "image/png", 64, 64, 1, null, "png", 16, true);
        String id = "00000000-0000-4000-8000-0000000000e1";
        JSONObject prepared = ProcessingRecord.build(session, live(), null, still, null, null, HOME, EmbeddedRecordCheck::identify, id);

        // -- the embedded copy and the shortened copy, as objects
        JSONObject png = new JSONObject(ProcessingRecord.pngText(prepared));
        ProcessingRecord.writeJson(out.resolve("embedded-png.hfrec.json"), png);
        expect("embedded: output.sha256 is null", png.getJSONObject("output").isNull("sha256"));
        expect("embedded: native by reference to the hfstudio chunk", !png.has("native") && "png:tEXt:hfstudio".equals(png.optString("native_ref")));
        expect("embedded: the same record_id", id.equals(png.getString("record_id")));
        expect("pngText is ASCII (a tEXt chunk is Latin-1)", ProcessingRecord.pngText(prepared).chars().allMatch(c -> c >= 0x20 && c < 0x7F));
        expect("exrText references the hfstudio attribute", "exr:attr:hfstudio".equals(new JSONObject(ProcessingRecord.exrText(prepared)).optString("native_ref")));
        expect("embedded() leaves its input alone", prepared.has("native") && !prepared.has("native_ref"));

        JSONObject movieCopy = new JSONObject(ProcessingRecord.movieText(prepared, true));
        ProcessingRecord.writeJson(out.resolve("embedded-movie.hfrec.json"), movieCopy);
        JSONObject ml = movieCopy.getJSONArray("layers").getJSONObject(0);
        expect("shortened: sources emptied, source_count 2", ml.getJSONArray("sources").isEmpty() && ml.getInt("source_count") == 2);
        expect("shortened: source_set_sha256 still matches the full record",
                ml.getString("source_set_sha256").equals(prepared.getJSONArray("layers").getJSONObject(0).getString("source_set_sha256")));
        expect("shortened: native_ref mp4:comment when the comment holds the scene", "mp4:comment".equals(movieCopy.optString("native_ref")));
        expect("shortened: native kept when the comment was cut", new JSONObject(ProcessingRecord.movieText(prepared, false)).has("native"));

        // -- PNG: a written file with every chunk, then its sidecar
        File dir = Files.createTempDirectory("hfs-embedded-png").toFile();
        Path file = new File(dir, "frame0001.png").toPath();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        Provenance.writePngChunk(file, session);
        ExportWriter.stampRecord(file, prepared, ProcessingRecord.pngText(prepared));
        Path side = ProcessingRecord.writePrepared(file, prepared);
        JSONObject sidecar = new JSONObject(Files.readString(side, StandardCharsets.UTF_8));
        JSONObject inFile = new JSONObject(Provenance.readPngText(file, ProcessingRecord.EMBED_KEY));
        expect("the sidecar hashes the file as written, chunks and all", SourceIdentity.sha256(file).equals(sidecar.getJSONObject("output").getString("sha256")));
        expect("the PNG's hfrecord chunk equals the sidecar (sha256 null, no file_name, native_ref)",
                asEmbedded(sidecar, "png:tEXt:hfstudio").similar(inFile));
        expect("the hfstudio chunk is still the session", session.similar(Provenance.readPngChunk(file)));
        String suite = Provenance.readPngText(file, ProcessingRecord.SUITE_PNG_KEYWORD);
        expect("HelioSoftware-Provenance holds the suite object", suite != null && sidecar.getJSONObject("provenance").similar(new JSONObject(suite)));
        String stamp = sidecar.getJSONObject("provenance").getString("rhef");
        expect("RHEF chunk is the stamp, with the Disclaimer beside it", stamp.equals(Provenance.readPngText(file, "RHEF"))
                && ProcessingRecord.SUITE_DISCLAIMER.equals(Provenance.readPngText(file, "Disclaimer")));

        // -- the ffmpeg command pieces
        expect("ffmetadata escapes = ; # backslash and newline",
                ExportWriter.ffmetadataEscape("a=b;c#d\\e\nf").equals("a\\=b\\;c\\#d\\\\e\\\nf"));
        List<String> enc = ExportWriter.withMetadataTags(ExportWriter.encodeArgs(ExportFormat.H264, ExportFormat.Chroma.YUV420,
                ExportFormat.Depth.EIGHT, 64, 64, 24, false));
        int mf = enc.indexOf("-movflags");
        expect("one -movflags, with use_metadata_tags added: " + (mf < 0 ? "none" : enc.get(mf + 1)),
                enc.stream().filter("-movflags"::equals).count() == 1 && enc.get(mf + 1).equals("+faststart+write_colr+use_metadata_tags"));

        mp4(session, prepared);

        if (failures != 0) {
            System.out.println(failures + " embedded record failure(s)");
            System.exit(1);
        }
    }

    /** A one-frame H.264 export through ExportWriter and the ffmpeg this build ships; its tags read back. */
    private static void mp4(JSONObject session, JSONObject prepared) throws Exception {
        Path ffmpeg = Path.of(Directories.libCacheDir, "ffmpeg");
        try (InputStream in = FileUtils.getResource(Platform.getResourceDir() + "ffmpeg")) {
            Files.copy(in, ffmpeg, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            expect("the natives jar carries ffmpeg for " + Platform.getResourceDir() + ": " + e.getMessage(), false);
            return;
        }
        ffmpeg.toFile().setExecutable(true);

        ProcessingRecord.Output movie = new ProcessingRecord.Output("movie", "video/mp4", 64, 64, 1, 24, "H.264", 8, true);
        JSONObject record = ProcessingRecord.build(session, live(), null, movie, null, null, HOME, EmbeddedRecordCheck::identify,
                "00000000-0000-4000-8000-0000000000e2");
        ExportWriter writer = new ExportWriter(ExportFormat.H264, ExportFormat.Chroma.YUV420, ExportFormat.Depth.EIGHT, 64, 64, 24, false, session);
        BufferedImage frame = MappedImageFactory.createRGBImage(64, 64);
        try {
            writer.encode(frame, null, -1, 3);
        } finally {
            MappedImageFactory.free(frame);
        }
        String written = writer.close(record);
        expect("the movie was written", written != null && Files.isRegularFile(Path.of(written)));
        if (written == null)
            return;

        Map<String, String> tags = readTags(ffmpeg, Path.of(written));
        String expected = ProcessingRecord.movieText(record, new JSONObject(Provenance.metadataComment(session)).has("scene"));
        expect("hfrecord tag read back is the shortened record (" + expected.length() + " chars)", expected.equals(tags.get("hfrecord")));
        expect("description is the suite credit line", record.getJSONObject("provenance").getString("credit").equals(tags.get("description")));
        expect("comment is still the session, as before", Provenance.metadataComment(session).equals(tags.get("comment")));

        String ffprobe = onPath("ffprobe");
        if (ffprobe != null) {
            Process p = new ProcessBuilder(ffprobe, "-v", "error", "-show_entries", "format_tags=hfrecord", "-of", "json", written)
                    .redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(60, TimeUnit.SECONDS);
            JSONObject tagsJson = new JSONObject(text).getJSONObject("format").optJSONObject("tags");
            expect("ffprobe reads the same hfrecord tag", tagsJson != null && expected.equals(tagsJson.optString("hfrecord")));
        } else
            System.out.println("  (no ffprobe on the PATH: read back with the bundled ffmpeg only)");
    }

    /** The global tags of a file, as ffmpeg writes them to an ffmetadata file. */
    private static Map<String, String> readTags(Path ffmpeg, Path movie) throws IOException, InterruptedException {
        Path meta = Files.createTempFile("tags", ".txt");
        Process p = new ProcessBuilder(ffmpeg.toString(), "-v", "error", "-y", "-i", movie.toString(), "-f", "ffmetadata", meta.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor(60, TimeUnit.SECONDS);
        Map<String, String> tags = new HashMap<>();
        String text = Files.readString(meta, StandardCharsets.UTF_8);
        StringBuilder line = new StringBuilder();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) { // unescape: a backslash takes the next character as it is
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                line.append('\u0000').append(text.charAt(++i));
            } else if (c == '\n') {
                lines.add(line.toString());
                line.setLength(0);
            } else
                line.append(c);
        }
        for (String l : lines) {
            int eq = -1;
            for (int i = 0; i < l.length(); i++) {
                if (l.charAt(i) == '\u0000') {
                    i++;
                    continue;
                }
                if (l.charAt(i) == '=') {
                    eq = i;
                    break;
                }
            }
            if (eq > 0 && !l.startsWith(";"))
                tags.put(l.substring(0, eq).replace("\u0000", ""), l.substring(eq + 1).replace("\u0000", ""));
        }
        return tags;
    }

    private static String onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null)
            return null;
        for (String d : path.split(File.pathSeparator)) {
            File f = new File(d, name);
            if (f.canExecute())
                return f.getPath();
        }
        return null;
    }
}
