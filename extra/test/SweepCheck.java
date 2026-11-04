package org.helioviewer.jhv.study;

import java.util.List;

import org.json.JSONObject;

/**
 * Sweeps (parameter study, WP7): step order, the inherit-only rule, the count math, the run limit and
 * the movie refusal. Headless; the "master frames" time source needs a live scene and is not exercised.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.study.SweepCheck
 */
public final class SweepCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            expect("no exception: " + t, false);
        }
        System.out.println(failures == 0 ? "SweepCheck: ok" : "SweepCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String job(String sweep, String output) {
        return """
                {"hfstudy":1,"scene":"/data/s.jhv","variants":[
                  {"name":"a","projection":"Orthographic"},
                  {"name":"b","projection":"Helioradial","warpLambda":0.5,"camera":{"fov":0.02}},
                  {"name":"c","enabled":false}],
                 "sweep":%s,"output":%s}""".formatted(sweep, output);
    }

    private static Study study(String sweep) {
        return Study.fromJson(new JSONObject(job(sweep, "{\"name\":\"{variant}\"}")));
    }

    private static void run() {
        String t4 = "{\"kind\":\"time\",\"start\":\"2026-01-12T00:00:00\",\"step\":\"6h\",\"count\":4}";
        Study ts = study(t4);
        List<StudyRunner.Step> steps = StudyRunner.plan(ts);
        expect("time sweep: 4 times x 2 enabled variants = 8 steps", steps.size() == 8 && Sweep.count(ts) == 8);
        boolean order = true;
        for (int i = 0; i < steps.size(); i++) {
            order &= steps.get(i).variant().name().equals(i % 2 == 0 ? "a" : "b"); // variant inner
            order &= steps.get(i).timeMilli().equals(steps.get(i / 2 * 2).timeMilli()); // same time within a pair
            order &= i < 2 || steps.get(i).timeMilli() >= steps.get(i - 1).timeMilli(); // time outer, rising
            order &= steps.get(i).index() == i + 1;
        }
        expect("time outer, variant inner, 1-based index", order);
        expect("step 6h is 6 hours apart", steps.get(2).timeMilli() - steps.get(0).timeMilli() == 6 * 3600_000L);
        expect("explicit times list", StudyRunner.plan(study("{\"kind\":\"time\",\"times\":[\"2026-01-12T00:00:00\",\"2026-01-12T01:00:00Z\"]}")).size() == 4);
        expect("steps 30s 5m 1d parse", TimeSweep.parseStep("30s") == 30_000L && TimeSweep.parseStep("5m") == 300_000L && TimeSweep.parseStep("1d") == 86_400_000L);
        expect("name appends the swept time", NamePattern.expand("{variant}", StudyRunner.fields(ts, steps.get(0), new JSONObject(), 8, steps.get(0).timeMilli()), ts.sweep().sweptField())
                .equals("a_time-2026-01-12T0000"));

        Study fs = study("{\"kind\":\"field\",\"field\":\"warpLambda\",\"values\":[-1,0,1]}");
        List<StudyRunner.Step> f = StudyRunner.plan(fs);
        expect("field sweep: a inherits (3 copies), b overrides (once) = 4 steps", f.size() == 4 && Sweep.count(fs) == 4);
        expect("inheriting row gets each value injected",
                f.get(0).variant().override().getDouble("warpLambda") == -1 && f.get(2).variant().override().getDouble("warpLambda") == 1
                        && f.get(1).extraFields().get("lambda").equals("0"));
        expect("overriding row emitted once, unchanged", f.get(3).variant().name().equals("b") && f.get(3).variant().override().getDouble("warpLambda") == 0.5
                && f.get(3).extraFields().isEmpty() && f.get(3).timeMilli() == null);
        expect("source rows are not edited", !fs.variants.get(0).override().has("warpLambda"));
        Study fov = study("{\"kind\":\"field\",\"field\":\"fov\",\"start\":0.1,\"step\":0.1,\"count\":2}");
        List<StudyRunner.Step> g = StudyRunner.plan(fov);
        expect("fov: camera override means b is left alone, a gets 2", g.size() == 3 && g.get(0).variant().override().getJSONObject("camera").getDouble("fov") == 0.1
                && g.get(2).variant().override().getJSONObject("camera").getDouble("fov") == 0.02);
        expect("no sweep gives plain variant steps", StudyRunner.plan(study("null")).size() == 2 && Sweep.count(study("null")) == 2);

        expect("size estimate is count x default pixels x bytes per pixel",
                Sweep.estimateBytes(ts) == (long) (8 * Sweep.DEFAULT_W * Sweep.DEFAULT_H * Sweep.EST_BYTES_PER_PIXEL));

        boolean atLimit = true;
        try {
            Sweep.checkLimit(500, 500);
        } catch (IllegalArgumentException e) {
            atLimit = false;
        }
        expect("at the limit is allowed", atLimit);
        boolean refused = false;
        try {
            Sweep.checkLimit(501, 500);
        } catch (IllegalArgumentException e) {
            refused = e.getMessage().contains("501") && e.getMessage().contains("500");
        }
        expect("above the limit is refused with the numbers", refused);
        expect("run limit default is 500", Sweep.DEFAULT_RUN_LIMIT == 500);

        String msg = null;
        try {
            StudyRunner.plan(Study.fromJson(new JSONObject(job(t4, "{\"movies\":{\"format\":\"mp4\"}}"))));
        } catch (IllegalArgumentException e) {
            msg = e.getMessage();
        }
        expect("time sweep with movies is refused", "A time sweep and movie output cannot be combined: pick one.".equals(msg));
        expect("field sweep with movies is allowed",
                StudyRunner.plan(Study.fromJson(new JSONObject(job("{\"kind\":\"field\",\"field\":\"zoom\",\"values\":[1,2]}", "{\"movies\":{\"format\":\"mp4\"}}")))).size() == 4);

        for (String sw : new String[]{t4, "{\"kind\":\"field\",\"field\":\"fov\",\"values\":[0.1],\"future\":{\"x\":1}}", "{\"kind\":\"time\",\"frames\":\"master\",\"note\":\"n\"}"}) {
            JSONObject in = new JSONObject(job(sw, "{\"name\":\"{variant}\"}"));
            expect("job with sweep round-trips: " + new JSONObject(sw).optString("kind") + (sw.contains("future") ? " with an unknown key" : ""),
                    Study.fromJson(in).toJson().similar(in));
        }
    }
}
