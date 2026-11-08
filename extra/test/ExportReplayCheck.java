package org.helioviewer.jhv.movie;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.json.JSONArray;
import org.json.JSONObject;

/** ExportReplay: the scene of a record, one frame of a movie, and the verdicts. */
public final class ExportReplayCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        failures += ok ? 0 : 1;
    }

    private static final String STATE = "org.helioviewer.jhv.state";

    private static JSONObject layer(String id, String mode, int n) {
        JSONArray uris = new JSONArray();
        for (int i = 0; i < n; i++)
            uris.put("file:~/data/" + id + "-" + i + ".fits");
        JSONObject data = new JSONObject().put("uris", uris).put("imageParams", new JSONObject().put("differenceMode", mode));
        return new JSONObject().put("id", id).put("data", data);
    }

    private static JSONObject movieRecord() {
        JSONArray layers = new JSONArray().put(layer("plain", "None", 5)).put(layer("run", "Running", 5)).put(layer("unmapped", "None", 5));
        JSONObject session = new JSONObject().put(STATE, new JSONObject().put("imageLayers", layers));
        JSONObject by = new JSONObject().put("plain", new JSONArray().put(3).put(4)).put("run", new JSONArray().put(2).put(3));
        JSONArray rl = new JSONArray();
        for (String id : new String[]{"plain", "run"})
            rl.put(new JSONObject().put("id", id).put("sources", new JSONArray().put(new JSONObject()).put(new JSONObject()).put(new JSONObject()).put(new JSONObject()).put(new JSONObject())));
        return new JSONObject().put("layers", rl).put("hfrecord", "1.1").put("output", new JSONObject().put("kind", "movie").put("frames", 2).put("width", 64).put("height", 32))
                .put("native", new JSONObject().put("studio_session", session))
                .put("frame_map", new JSONObject().put("times", new JSONArray().put("a").put("b")).put("by_layer", by));
    }

    private static List<String> uris(JSONObject session, int layer) {
        List<String> out = new ArrayList<>();
        for (Object o : session.getJSONObject(STATE).getJSONArray("imageLayers").getJSONObject(layer).getJSONObject("data").getJSONArray("uris"))
            out.add(o.toString());
        return out;
    }

    public static void main(String[] args) throws IOException {
        JSONObject rec = movieRecord();
        String home = System.getProperty("user.home", "");
        JSONObject scene = ExportReplay.sessionOf(rec);
        expect("the scene has the home directory put back", uris(scene, 0).get(0).startsWith("file:" + home.replace('\\', '/')) || uris(scene, 0).get(0).startsWith("file://" + home));
        expect("sessionOf does not change the record", rec.toString().contains("file:~/data/plain-0.fits"));
        expect("a record without a scene has none", ExportReplay.sessionOf(new JSONObject().put("hfrecord", "1.1")) == null);

        expect("a movie record is a movie", ExportReplay.isMovie(rec));
        expect("a still record is not", !ExportReplay.isMovie(new JSONObject().put("output", new JSONObject().put("frames", 1))));
        expect("the whole range is not the default", !ExportReplay.wholeRange(rec, false));
        expect("it is when asked", ExportReplay.wholeRange(rec, true));
        expect("it is when the writer says so", ExportReplay.wholeRange(new JSONObject().put("reproducibility", new JSONObject().put("replay_scope", "range")), false));

        List<String> notes = new ArrayList<>();
        JSONObject narrowed = new JSONObject(rec.toString());
        JSONObject session = ExportReplay.sessionOf(narrowed);
        expect("narrowing reports success", ExportReplay.narrow(session, narrowed, notes));
        expect("a plain layer keeps only the first output frame's source", uris(session, 0).size() == 1 && uris(session, 0).get(0).endsWith("plain-3.fits"));
        expect("a running difference keeps the frame before it as well", uris(session, 1).size() == 2
                && uris(session, 1).get(0).endsWith("run-1.fits") && uris(session, 1).get(1).endsWith("run-2.fits"));
        expect("a layer the frame map does not cover loads whole", uris(session, 2).size() == 5);
        JSONObject mismatched = new JSONObject(rec.toString());
        mismatched.getJSONArray("layers").getJSONObject(0).getJSONArray("sources").remove(0);
        JSONObject ms = ExportReplay.sessionOf(mismatched);
        ExportReplay.narrow(ms, mismatched, new ArrayList<>());
        expect("a layer whose source list differs from the scene's uris loads whole", uris(ms, 0).size() == 5);
        expect("a note says what was done", notes.size() == 1 && notes.get(0).contains("first output frame"));

        expect("a base difference needs frame 0 too", ExportReplay.framesNeeded("Base", false, 4, 9).equals(java.util.Set.of(0, 4)));
        expect("a sequence filter needs every frame", ExportReplay.framesNeeded("None", true, 2, 3).size() == 4);

        Path dir = Files.createTempDirectory("replaycheck");
        boolean refused = false;
        try {
            ExportReplay.prepare(new JSONObject().put("hfrecord", "1.1"), dir.resolve("x.png.hfrec"), false, dir);
        } catch (IOException e) {
            refused = e.getMessage().contains("no scene");
        }
        expect("a record without a scene cannot be prepared, and says why", refused);
        ExportReplay.Prepared whole = ExportReplay.prepare(rec, dir.resolve("x.mp4.hfrec"), true, dir);
        expect("range loads every source", uris(whole.session(), 0).size() == 5 && whole.movie() && !whole.narrowed());

        JSONObject a = new JSONObject().put("output", new JSONObject().put("width", 4).put("height", 4).put("pixel_sha256", "a".repeat(64)));
        JSONObject same = new JSONObject(a.toString());
        expect("equal digests are identical", ExportReplay.verdict(a, same, null, null).verdict() == ExportReplay.Verdict.IDENTICAL);
        JSONObject other = new JSONObject(a.toString());
        other.getJSONObject("output").put("pixel_sha256", "b".repeat(64));
        expect("different digests with no pictures cannot be checked", ExportReplay.verdict(a, other, null, null).verdict() == ExportReplay.Verdict.CANNOT_CHECK);
        JSONObject bigger = new JSONObject(a.toString());
        bigger.getJSONObject("output").put("width", 8).put("pixel_sha256", "c".repeat(64));
        ExportReplay.Outcome size = ExportReplay.verdict(a, bigger, null, null);
        expect("a different size cannot be checked, and says the sizes", size.verdict() == ExportReplay.Verdict.CANNOT_CHECK && size.why().contains("8 by 4"));

        Path p1 = picture(dir, "p1.png", 100), p2 = picture(dir, "p2.png", 100), p3 = picture(dir, "p3.png", 101), p4 = picture(dir, "p4.png", 160);
        expect("with no tolerance set, differing digests are different even for the same picture", ExportReplay.verdict(a, other, p1, p2).verdict() == ExportReplay.Verdict.DIFFERENT);
        System.setProperty("jhv.replayTolerance", "1.0");
        expect("with a tolerance set, the same picture is equivalent", ExportReplay.verdict(a, other, p1, p2).verdict() == ExportReplay.Verdict.EQUIVALENT);
        expect("one level of 255 off is within a tolerance of 1", ExportReplay.verdict(a, other, p1, p3).verdict() == ExportReplay.Verdict.EQUIVALENT);
        expect("sixty levels off is different", ExportReplay.verdict(a, other, p1, p4).verdict() == ExportReplay.Verdict.DIFFERENT);
        System.clearProperty("jhv.replayTolerance");

        if (failures > 0) {
            System.out.println("ExportReplayCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("ExportReplayCheck: all passed");
    }

    private static Path picture(Path dir, String name, int grey) throws IOException {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++)
                img.setRGB(x, y, (grey << 16) | (grey << 8) | grey);
        File f = dir.resolve(name).toFile();
        ImageIO.write(img, "png", f);
        return f.toPath();
    }
}
