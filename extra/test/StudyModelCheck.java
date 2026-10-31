package org.helioviewer.jhv.study;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

/** Study model: job file round trip, inheritance, name expansion, and a session without a study left alone. */
public final class StudyModelCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final String EXAMPLE = """
            {
              "hfstudy": 1,
              "scene": "2025-09-21_CONNECT.jhv",
              "variants": [
                {"name": "linear",     "projection": "Helioradial", "warpLambda": 1.0},
                {"name": "log",        "projection": "Helioradial", "warpLambda": 0.0},
                {"name": "reciprocal", "projection": "Helioradial", "warpLambda": -1.0},
                {"name": "unrolled",   "projection": "HelioradialUnrolled", "warpLambda": 0.0}
              ],
              "sweep": null,
              "output": {
                "folder": "~/Exports/{scene}_{date}",
                "name": "{time}_{variant}",
                "stills": {"size": [2048, 2048]},
                "movies": {"format": "H264_GOOD", "range": "playback", "longSide": 2048},
                "contactSheet": "grid",
                "record": "sidecar+manifest",
                "overwrite": "skip-if-unchanged"
              }
            }""";

    public static void main(String[] args) throws Exception {
        JSONObject ex = new JSONObject(EXAMPLE);
        Study s = Study.fromJson(ex);
        expect("the example parses to four variants", s.variants.size() == 4 && s.variants.get(3).name().equals("unrolled"));
        expect("the example round-trips", s.toJson().similar(ex));
        expect("output block fields", s.output().overwrite() == Overwrite.SKIP_IF_UNCHANGED
                && s.output().name().equals("{time}_{variant}") && s.output().stills().getJSONArray("size").getInt(0) == 2048
                && s.output().movies().getString("format").equals("H264_GOOD"));

        JSONObject withExtras = new JSONObject(EXAMPLE).put("futureKey", new JSONObject().put("a", 1));
        withExtras.getJSONArray("variants").getJSONObject(0).put("pizza", "wedges").put("newThing", 7);
        withExtras.getJSONObject("output").put("alsoNew", "x");
        expect("unknown keys round-trip at every level", Study.fromJson(withExtras).toJson().similar(withExtras));

        Path f = Files.createTempFile("study", ".hfstudy.json");
        StudyIO.write(s, f);
        expect("file write then read matches", StudyIO.read(f).toJson().similar(ex));
        String h = StudyIO.sha256(f);
        expect("sha256 is 64 hex", h.matches("[0-9a-f]{64}"));
        Files.writeString(f, new JSONObject(EXAMPLE).put("hfstudy", 2).toString());
        try {
            StudyIO.read(f);
            expect("a newer version is refused", false);
        } catch (java.io.IOException e) {
            expect("a newer version is refused with a clear message", e.getMessage().contains("version 2") && e.getMessage().contains("Update"));
        }
        Files.writeString(f, "{\"scene\": \"x\"}");
        try {
            StudyIO.read(f);
            expect("a file with no version is refused", false);
        } catch (java.io.IOException e) {
            expect("a file with no version is refused", true);
        }
        Files.delete(f);

        // Inheritance: every key the row names replaces the scene's; every other key is kept.
        JSONObject scene = new JSONObject("""
                {"projection":"Orthographic","warpLambda":0.5,"helioradial3D":false,"surfaceModel":"flat",
                 "camera":{"fov":10.0,"zoom":2.0,"translation":[0,0],"dragRotation":[0,0,0,1]}}""");
        Variant none = new Variant("none", true, new JSONObject());
        expect("an empty row inherits the whole scene view", none.resolve(scene).similar(scene));
        for (String k : new String[]{"projection", "warpLambda", "helioradial3D", "surfaceModel"}) {
            Object val = k.equals("helioradial3D") ? (Object) true : k.equals("warpLambda") ? (Object) (-1.0) : (Object) "Other";
            JSONObject r = new Variant("v", true, new JSONObject().put(k, val)).resolve(scene);
            JSONObject expected = new JSONObject(scene.toString()).put(k, val);
            expect("override " + k + " replaces only " + k, r.similar(expected));
        }
        for (String k : new String[]{"fov", "zoom"}) {
            JSONObject r = new Variant("v", true, new JSONObject().put("camera", new JSONObject().put(k, 99.0))).resolve(scene);
            JSONObject expected = new JSONObject(scene.toString());
            expected.getJSONObject("camera").put(k, 99.0);
            expect("override camera." + k + " keeps the other camera keys", r.similar(expected));
        }
        JSONObject r = new Variant("v", true, new JSONObject("{\"camera\":{\"translation\":[1,2],\"dragRotation\":[1,0,0,0]}}")).resolve(scene);
        expect("override camera translation and rotation", r.getJSONObject("camera").getJSONArray("translation").getInt(1) == 2
                && r.getJSONObject("camera").getJSONArray("dragRotation").getInt(0) == 1 && r.getJSONObject("camera").getDouble("fov") == 10.0);
        r = new Variant("v", true, new JSONObject("{\"layers\":{\"AIA 171\":false},\"size\":[800,600],\"pizza\":\"strips\"}")).resolve(scene);
        expect("layers, size and pizza pass through", !r.getJSONObject("layers").getBoolean("AIA 171")
                && r.getJSONArray("size").getInt(0) == 800 && r.getString("pizza").equals("strips")
                && r.getString("projection").equals("Orthographic"));
        Variant vv = new Variant("v", true, new JSONObject("{\"fov\":1}"));
        vv.resolve(scene).put("fov", 5);
        expect("resolving does not touch the row or the scene", vv.override().getInt("fov") == 1 && scene.getJSONObject("camera").getDouble("fov") == 10.0);
        Variant off = new Variant("off", false, new JSONObject());
        expect("a disabled row writes enabled:false, an enabled one writes nothing", !off.toJson().getBoolean("enabled") && !none.toJson().has("enabled"));

        // Names.
        long t = java.time.Instant.parse("2025-09-21T22:35:47Z").toEpochMilli();
        Map<String, String> f2 = new HashMap<>();
        f2.put("scene", "CONNECT");
        f2.put("variant", "log");
        f2.put("time", NamePattern.time(t));
        f2.put("projection", "Helioradial");
        f2.put("pizza", "off");
        f2.put("fov", "12.5");
        f2.put("zoom", "2");
        f2.put("lambda", "0");
        f2.put("index", NamePattern.index(2, 12));
        f2.put("date", NamePattern.date(t));
        expect("{time} is yyyy-MM-ddTHHmm UTC", f2.get("time").equals("2025-09-21T2235"));
        expect("{index} pads to the row count", f2.get("index").equals("02") && NamePattern.index(3, 4).equals("3") && NamePattern.index(3, 100).equals("003"));
        for (String k : f2.keySet())
            expect("{" + k + "} expands", NamePattern.expand("a_{" + k + "}_b", f2).equals("a_" + f2.get(k) + "_b"));
        expect("the default pattern", NamePattern.expand(StudyOutput.DEFAULT_NAME, f2).equals("2025-09-21T2235_log"));
        expect("an unknown field is left as written", NamePattern.expand("{nope}_{variant}", f2).equals("{nope}_log"));
        f2.put("variant", "a/b:c");
        expect("path separators in a value cannot escape the folder", NamePattern.expand("{variant}", f2).equals("a_b_c"));
        f2.put("variant", "log");
        f2.put("zoom", "4");
        expect("a swept field absent from the pattern is appended", NamePattern.expand("{variant}", f2, "zoom").equals("log_zoom-4"));
        expect("a swept field already in the pattern is not appended", NamePattern.expand("{variant}_{zoom}", f2, "zoom").equals("log_4"));

        // The session: no study means no key.
        Study.CURRENT.set(null);
        JSONObject main = new JSONObject("{\"time\":\"x\",\"layers\":[]}");
        String before = main.toString();
        Study.addTo(main);
        expect("a session without a study is byte-identical", main.toString().equals(before));
        Study.CURRENT.set(s);
        Study.addTo(main);
        expect("a session with a study carries it", main.getJSONObject("study").similar(ex));
        Study.CURRENT.set(null);
        Study.readFrom(main);
        expect("reading a session restores the study", Study.CURRENT.get() != null && Study.CURRENT.get().toJson().similar(ex));
        Study.readFrom(new JSONObject(before));
        expect("reading a session without one clears it", Study.CURRENT.get() == null);

        if (failures > 0) {
            System.out.println("StudyModelCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("StudyModelCheck: all passed");
    }
}
