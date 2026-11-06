package org.helioviewer.jhv.study;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The still runner (parameter study, WP3): the fingerprint skip-if-unchanged compares, the sidecar and
 * manifest it writes, the command it prints, and one real headless run of a two-variant study.
 *
 * <p>The real run starts HFStudio.jar from the working directory with a throwaway home and needs a
 * display (xvfb on Linux); it prints a lowercase skip line unless HFS_STUDY_E2E=1 is set and the jar exists.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.study.StudyRunnerCheck
 */
public final class StudyRunnerCheck {

    private static final String IMAGE = "extra/test/data/syn_AIA_171_2026-01-12T00-00-00_f_V3.fits";

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        try {
            pure();
            scratch();
            endToEnd();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            expect("no exception: " + t, false);
        }
        System.out.println(failures == 0 ? "StudyRunnerCheck: ok" : "StudyRunnerCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static JSONObject job() {
        return new JSONObject("""
                {"hfstudy":1,"scene":"/data/Figure1.jhv","variants":[
                  {"name":"ortho","projection":"Orthographic"},
                  {"name":"radial","projection":"Helioradial","warpLambda":0.5,"camera":{"fov":0.02}}],
                 "output":{"folder":"~/Exports/{scene}_{date}","name":"{index}_{variant}","overwrite":"skip-if-unchanged"}}""");
    }

    private static void scratch() throws Exception {
        Path root = Files.createTempDirectory("study-scratch");
        Path run = Files.createDirectories(root.resolve("run"));
        Path tmp = Files.createDirectories(root.resolve("HFStudio_x"));
        Files.writeString(tmp.resolve("frame0001.png"), "a");
        Files.writeString(tmp.resolve("frame0001.png.hfrec"), "{}");
        Files.writeString(tmp.resolve("frame0001.png.hfrec.json"), "{}"); // an older sidecar name goes too
        StudyRunner.takeScratch(tmp.resolve("frame0001.png"), run.resolve("1.png"), run);
        expect("scratch picture lands in the run folder", Files.exists(run.resolve("1.png")));
        expect("emptied scratch folder is gone", !Files.exists(tmp));
        Path other = Files.createDirectories(root.resolve("HFStudio_y"));
        Files.writeString(other.resolve("frame0001.png"), "b");
        Files.writeString(other.resolve("keep.txt"), "c");
        StudyRunner.takeScratch(other.resolve("frame0001.png"), run.resolve("2.png"), run);
        expect("a scratch folder holding other files stays", Files.exists(other.resolve("keep.txt")));
        Files.writeString(run.resolve("3.png"), "d");
        StudyRunner.takeScratch(run.resolve("3.png"), run.resolve("3.png"), run);
        expect("a picture already in the run folder keeps its folder", Files.exists(run));
        // A movie is written straight into Exports; removing that folder made every later movie of the run fail
        Path exports = Files.createDirectories(root.resolve("Exports"));
        Files.writeString(exports.resolve("HFStudio_t.mp4"), "e");
        Files.writeString(exports.resolve("HFStudio_t.mp4.hfrec"), "{}");
        StudyRunner.takeScratch(exports.resolve("HFStudio_t.mp4"), run.resolve("4.mp4"), run, exports);
        expect("a movie taken from Exports leaves the Exports folder", Files.isDirectory(exports));
        expect("and takes the movie's own sidecar out of Exports", !Files.exists(exports.resolve("HFStudio_t.mp4.hfrec")));
    }

    private static void pure() throws Exception {
        Study study = Study.fromJson(job());
        JSONObject scene = new JSONObject("{\"projection\":\"Orthographic\",\"warpLambda\":0,\"camera\":{\"fov\":0.5,\"zoom\":1}}");
        JSONObject r1 = study.variants.get(1).resolve(scene);

        List<StudyRunner.Step> steps = StudyRunner.plan(study);
        expect("plan has one step per enabled variant, in order", steps.size() == 2 && steps.get(1).variant().name().equals("radial"));
        study.variants.get(0).setEnabled(false);
        expect("a disabled variant is not planned", StudyRunner.plan(study).size() == 1);
        study.variants.get(0).setEnabled(true);

        String name = NamePattern.expand("{index}_{variant}", StudyRunner.fields(study, steps.get(1), r1, 2, 0L));
        expect("name pattern: index and variant", name.equals("2_radial"));
        expect("fields carry lambda and fov from the resolved view",
                StudyRunner.fields(study, steps.get(1), r1, 2, 0L).get("lambda").equals("0.5")
                        && StudyRunner.fields(study, steps.get(1), r1, 2, 0L).get("fov").equals("0.02"));

        String a = StudyRunner.canon(new JSONObject("{\"b\":1,\"a\":{\"y\":2,\"x\":[1,2]}}"));
        expect("canon ignores key order", a.equals(StudyRunner.canon(new JSONObject("{\"a\":{\"x\":[1,2],\"y\":2},\"b\":1}"))));
        String p1 = StudyRunner.fingerprint("abc", r1, 100L);
        expect("fingerprint is stable", p1.equals(StudyRunner.fingerprint("abc", new JSONObject(r1.toString()), 100L)));
        expect("fingerprint changes with the scene hash", !p1.equals(StudyRunner.fingerprint("abd", r1, 100L)));
        expect("fingerprint changes with the time", !p1.equals(StudyRunner.fingerprint("abc", r1, 101L)));
        expect("fingerprint changes with the view", !p1.equals(StudyRunner.fingerprint("abc", study.variants.get(0).resolve(scene), 100L)));

        Path dir = Files.createTempDirectory("hfs-study-runner");
        Path png = dir.resolve("2_radial.png");
        expect("nothing to skip before the file exists", !StudyRunner.skip(Overwrite.NEVER, png, p1));
        Files.write(png, new byte[]{1, 2, 3});
        expect("never skips an existing file", StudyRunner.skip(Overwrite.NEVER, png, p1));
        expect("always does not skip", !StudyRunner.skip(Overwrite.ALWAYS, png, p1));
        expect("skip-if-unchanged without a sidecar redoes it", !StudyRunner.skip(Overwrite.SKIP_IF_UNCHANGED, png, p1));
        StudyRunner.Output o = new StudyRunner.Output("2_radial.png", "radial", StudyIO.sha256(png), 3, false);
        JSONObject side = StudyRunner.sidecarJson(steps.get(1), r1, "abc", 100L, p1, o);
        Files.writeString(StudyRunner.sidecar(png), side.toString(2), StandardCharsets.UTF_8);
        expect("the sidecar sits beside the png with the study key", StudyRunner.sidecar(png).getFileName().toString().equals("2_radial.hfrec")
                && side.getJSONObject("study").getJSONObject("resolved").getString("projection").equals("Helioradial"));
        expect("skip-if-unchanged skips when the fingerprint matches", StudyRunner.skip(Overwrite.SKIP_IF_UNCHANGED, png, p1));
        Files.move(StudyRunner.sidecar(png), StudyRunner.legacySidecar(png));
        expect("and reads an older 2_radial.hfrec.json sidecar too", StudyRunner.skip(Overwrite.SKIP_IF_UNCHANGED, png, p1));
        expect("which it renames to 2_radial.hfrec once read", Files.isRegularFile(StudyRunner.sidecar(png)) && !Files.exists(StudyRunner.legacySidecar(png)));
        expect("skip-if-unchanged redoes it when the time moved",
                !StudyRunner.skip(Overwrite.SKIP_IF_UNCHANGED, png, StudyRunner.fingerprint("abc", r1, 101L)));

        JSONObject m = StudyRunner.manifest(study, "abc", List.of(o));
        JSONArray outs = m.getJSONArray("outputs");
        expect("manifest lists the output with its hash, size and variant", outs.length() == 1
                && outs.getJSONObject(0).getString("sha256").equals(o.sha256()) && outs.getJSONObject(0).getLong("bytes") == 3
                && outs.getJSONObject(0).getString("variant").equals("radial"));
        expect("manifest holds the resolved job and the scene hash", m.getJSONObject("job").getJSONArray("variants").length() == 2
                && m.getString("sceneSha256").equals("abc"));

        Path jobPath = Path.of("/tmp/my job.hfstudy.json"); // separators differ on Windows
        String cmd = StudyRunner.commandLine(study, jobPath);
        expect("commandLine is the headless invocation, quoted", cmd.startsWith("./run.sh -state /data/Figure1.jhv -study '" + jobPath + "' -out ")
                && cmd.contains("Figure1_"));
    }

    private static void endToEnd() throws Exception {
        if (!"1".equals(System.getenv("HFS_STUDY_E2E")) || !Files.isReadable(Path.of("HFStudio.jar")) || !Files.isReadable(Path.of(IMAGE))) {
            System.out.println("  skip end-to-end run: needs HFS_STUDY_E2E=1, HFStudio.jar (ant jar) and a display");
            return;
        }
        Path home = Files.createTempDirectory("hfs-study-home");
        Path out = Files.createTempDirectory("hfs-study-out").resolve("run");
        Path jobFile = home.resolve("two.hfstudy.json");
        Files.writeString(jobFile, new JSONObject("""
                {"hfstudy":1,"scene":"","variants":[
                  {"name":"ortho","projection":"Orthographic"},
                  {"name":"radial","projection":"Helioradial"}],
                 "output":{"name":"{index}_{variant}","overwrite":"skip-if-unchanged"}}""").toString(2));
        int code = launch(home, jobFile, out);
        expect("headless run exits 0", code == 0);
        List<String> names = list(out);
        expect("two PNGs", names.stream().filter(n -> n.endsWith(".png")).count() == 2);
        expect("two sidecars", names.stream().filter(n -> n.endsWith(".hfrec")).count() == 2);
        expect("one manifest and the job copy", names.contains("manifest.hfstudy.json") && names.contains("job.hfstudy.json"));
        JSONObject m = new JSONObject(Files.readString(out.resolve("manifest.hfstudy.json")));
        boolean match = m.getJSONArray("outputs").length() == 2;
        for (int i = 0; match && i < 2; i++) {
            JSONObject o = m.getJSONArray("outputs").getJSONObject(i);
            match = StudyIO.sha256(out.resolve(o.getString("name"))).equals(o.getString("sha256"));
        }
        expect("manifest sha256s match the files", match);
        expect("the two variants drew different pictures", !m.getJSONArray("outputs").getJSONObject(0).getString("sha256")
                .equals(m.getJSONArray("outputs").getJSONObject(1).getString("sha256")));
        long t0 = Files.getLastModifiedTime(out.resolve("1_ortho.png")).toMillis();
        Thread.sleep(1100);
        expect("second run exits 0", launch(home, jobFile, out) == 0);
        expect("second run with skip-if-unchanged writes no PNG again", Files.getLastModifiedTime(out.resolve("1_ortho.png")).toMillis() == t0);
    }

    private static List<String> list(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).toList();
        }
    }

    private static int launch(Path home, Path job, Path out) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home, "--enable-native-access=ALL-UNNAMED", "-jar", "HFStudio.jar",
                "-load", Path.of(IMAGE).toAbsolutePath().toString(), "-study", job.toString(), "-out", out.toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(home.resolve("app.out").toFile()).start();
        if (!p.waitFor(240, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }
}
